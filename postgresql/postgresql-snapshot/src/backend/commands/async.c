/*-------------------------------------------------------------------------
 *
 * async.c
 *	  Asynchronous notification: NOTIFY, LISTEN, UNLISTEN
 *
 * Portions Copyright (c) 1996-2009, PostgreSQL Global Development Group
 * Portions Copyright (c) 1994, Regents of the University of California
 *
 * IDENTIFICATION
 *	  $PostgreSQL: pgsql/src/backend/commands/async.c,v 1.149 2009/07/31 20:26:22 tgl Exp $
 *
 *-------------------------------------------------------------------------
 */

/*-------------------------------------------------------------------------
 * New Async Notification Model:
 *
 * 1. Multiple backends on same machine. Multiple backends listening on
 *	  several channels. (This was previously called a "relation" even though it
 *	  is just an identifier and has nothing to do with a database relation.)
 *
 * 2. There is one central queue in the form of Slru backed file based storage
 *    (directory pg_notify/), with several pages mapped into shared memory.
 *
 *    There is no central storage of which backend listens on which channel,
 *    every backend has its own list.
 *
 *    Every backend that is listening on at least one channel registers by
 *    entering its Pid into the array of all backends. It then scans all
 *    incoming notifications and compares the notified channels with its list.
 *
 *    In case there is a match it delivers the corresponding notification to
 *    its frontend.
 *
 * 3. The NOTIFY statement (routine Async_Notify) registers the notification
 *    in a list which will not be processed until at transaction end. Every
 *    notification can additionally send a "payload" which is an extra text
 *    parameter to convey arbitrary information to the recipient.
 *
 *    Duplicate notifications from the same transaction are sent out as one
 *    notification only. This is done to save work when for example a trigger
 *    on a 2 million row table fires a notification for each row that has been
 *    changed. If the applications needs to receive every single notification
 *    that has been sent, it can easily add some unique string into the extra
 *    payload parameter.
 *
 *    Once the transaction commits, AtCommit_NotifyBeforeCommit() performs the
 *    required changes regarding listeners (Listen/Unlisten) and then adds the
 *    pending notifications to the head of the queue. The head pointer of the
 *    queue always points to the next free position and a position is just a
 *    page number and the offset in that page. This is done before marking the
 *    transaction as committed in clog. If we run into problems writing the
 *    notifications, we can still call elog(ERROR, ...) and the transaction
 *    will roll back.
 *
 *    Once we have put all of the notifications into the queue, we return to
 *    CommitTransaction() which will then commit to clog.
 *
 *    We are then called another time (AtCommit_NotifyAfterCommit())and check
 *    if we need to signal the backends.
 *    In SignalBackends() we scan the list of listening backends and send a
 *    PROCSIG_NOTIFY_INTERRUPT to every backend that has set its Pid (We don't
 *    know which backend is listening on which channel so we need to send a
 *    signal to every listening backend).
 *
 * 4. Upon receipt of a PROCSIG_NOTIFY_INTERRUPT signal, the signal handler
 *	  can call inbound-notify processing immediately if this backend is idle
 *	  (ie, it is waiting for a frontend command and is not within a transaction
 *	  block).  Otherwise the handler may only set a flag, which will cause the
 *	  processing to occur just before we next go idle.
 *
 * 5. Inbound-notify processing consists of reading all of the notifications
 *	  that have arrived since scanning last time. We read every notification
 *	  until we reach the head pointer's position. Then we check if we were the
 *	  laziest backend: if our pointer is set to the same position as the global
 *	  tail pointer is set, then we set it further to the second-laziest
 *	  backend (We can identify it by inspecting the positions of all other
 *	  backends' pointers). Whenever we move the tail pointer we also truncate
 *	  now unused pages (i.e. delete files in pg_notify/ that are no longer
 *	  used).
 *	  Note that we really read _any_ available notification in the queue. We
 *	  also read uncommitted notifications from transaction that could still
 *	  roll back. We must not deliver the notifications of those transactions
 *	  but just copy them out of the queue. We save them in the
 *	  uncommittedNotifications list which we try to deliver every time we
 *	  check for available notifications.
 *
 * An application that listens on the same channel it notifies will get
 * NOTIFY messages for its own NOTIFYs.  These can be ignored, if not useful,
 * by comparing be_pid in the NOTIFY message to the application's own backend's
 * Pid.  (As of FE/BE protocol 2.0, the backend's Pid is provided to the
 * frontend during startup.)  The above design guarantees that notifies from
 * other backends will never be missed by ignoring self-notifies.
 *-------------------------------------------------------------------------
 */

/* XXX 
 *
 * TODO:
 *  - guc parameter max_notifies_per_txn ??
 *  - adapt comments
 */

#include "postgres.h"

#include <unistd.h>
#include <signal.h>

#include "access/heapam.h"
#include "access/slru.h"
#include "access/transam.h"
#include "access/twophase_rmgr.h"
#include "access/xact.h"
#include "catalog/pg_listener.h"
#include "commands/async.h"
#include "libpq/libpq.h"
#include "libpq/pqformat.h"
#include "miscadmin.h"
#include "storage/ipc.h"
#include "storage/procsignal.h"
#include "storage/sinval.h"
#include "tcop/tcopprot.h"
#include "utils/builtins.h"
#include "utils/fmgroids.h"
#include "utils/memutils.h"
#include "utils/ps_status.h"
#include "utils/tqual.h"


/*
 * State for pending LISTEN/UNLISTEN actions consists of an ordered list of
 * all actions requested in the current transaction. As explained above,
 * we don't actually send notifications until we reach transaction commit.
 *
 * The list is kept in CurTransactionContext.  In subtransactions, each
 * subtransaction has its own list in its own CurTransactionContext, but
 * successful subtransactions attach their lists to their parent's list.
 * Failed subtransactions simply discard their lists.
 */
typedef enum
{
	LISTEN_LISTEN,
	LISTEN_UNLISTEN,
	LISTEN_UNLISTEN_ALL
} ListenActionKind;

typedef enum
{
	READ_ALL_TO_UNCOMMITTED,
	READ_ONLY_COMMITTED
} QueueProcessType;

typedef enum
{
	SIGNAL_ALL,
	SIGNAL_SLOW
} SignalType;

typedef struct
{
	ListenActionKind action;
	char		condname[1];	/* actually, as long as needed */
} ListenAction;

static List *pendingActions = NIL;		/* list of ListenAction */

static List *upperPendingActions = NIL; /* list of upper-xact lists */

static List *uncommittedNotifications = NIL;

static bool needSignalBackends = false;

/*
 * State for outbound notifies consists of a list of all channels NOTIFYed
 * in the current transaction.	We do not actually perform a NOTIFY until
 * and unless the transaction commits.	pendingNotifies is NIL if no
 * NOTIFYs have been done in the current transaction.
 *
 * The list is kept in CurTransactionContext.  In subtransactions, each
 * subtransaction has its own list in its own CurTransactionContext, but
 * successful subtransactions attach their lists to their parent's list.
 * Failed subtransactions simply discard their lists.
 *
 * Note: the action and notify lists do not interact within a transaction.
 * In particular, if a transaction does NOTIFY and then LISTEN on the same
 * condition name, it will get a self-notify at commit.  This is a bit odd
 * but is consistent with our historical behavior.
 */

typedef struct Notification
{
	char		   *channel;
	char		   *payload;
	TransactionId	xid;
	union {
		/* we only need one of both, depending on whether we send a
 		 * notification or receive one. */
		int32		dstPid;
		int32		srcPid;
	};
} Notification;

typedef struct AsyncQueueEntry
{
	/*
	 * this record has the maximal length, but usually we limit it to
	 * AsyncQueueEntryEmptySize + strlen(payload).
	 */
	Size			length;
	Oid				dboid;
	TransactionId	xid;
	int32			srcPid;
	char			channel[NAMEDATALEN];
	char			payload[NOTIFY_PAYLOAD_MAX_LENGTH];
} AsyncQueueEntry;
#define AsyncQueueEntryEmptySize \
	 (sizeof(AsyncQueueEntry) - NOTIFY_PAYLOAD_MAX_LENGTH + 1)

#define	InvalidPid (-1)
#define QUEUE_POS_PAGE(x) ((x).page)
#define QUEUE_POS_OFFSET(x) ((x).offset)
#define QUEUE_POS_EQUAL(x,y) \
	 ((x).page == (y).page ? (x).offset == (y).offset : false)
#define SET_QUEUE_POS(x,y,z) \
	do { \
		(x).page = (y); \
		(x).offset = (z); \
	} while (0);
/* does page x logically precede page y with z = HEAD ? */
#define QUEUE_POS_MIN(x,y,z) \
	asyncQueuePagePrecedesLogically((x).page, (y).page, (z).page) ? (x) : \
		 asyncQueuePagePrecedesLogically((y).page, (x).page, (z).page) ? (y) : \
			 (x).offset < (y).offset ? (x) : \
			 	(y)
#define QUEUE_BACKEND_POS(i) asyncQueueControl->backend[(i)].pos
#define QUEUE_BACKEND_PID(i) asyncQueueControl->backend[(i)].pid
#define QUEUE_HEAD asyncQueueControl->head
#define QUEUE_TAIL asyncQueueControl->tail

typedef struct QueuePosition
{
	int				page;
	int				offset;
} QueuePosition;

typedef struct QueueBackendStatus
{
	int32			pid;
	QueuePosition	pos;
} QueueBackendStatus;

/*
 * The AsyncQueueControl structure is protected by the AsyncQueueLock.
 *
 * In SHARED mode, backends will only inspect their own entries as well as
 * head and tail pointers. Consequently we can allow a backend to update its
 * own record while holding only a shared lock (since no other backend will
 * inspect it).
 *
 * In EXCLUSIVE mode, backends can inspect the entries of other backends and
 * also change head and tail pointers.
 *
 * In order to avoid deadlocks, whenever we need both locks, we always first
 * get AsyncQueueLock and then AsyncCtlLock.
 */
typedef struct AsyncQueueControl
{
	QueuePosition		head;		/* head points to the next free location */
	QueuePosition 		tail;		/* the global tail is equivalent to the
									   tail of the "slowest" backend */
	TimestampTz			lastQueueFullWarn;	/* when the queue is full we only
											   want to log that once in a
											   while */
	QueueBackendStatus	backend[1];	/* actually this one has as many entries as
									 * connections are allowed (MaxBackends) */
	/* DO NOT ADD FURTHER STRUCT MEMBERS HERE */
} AsyncQueueControl;

static AsyncQueueControl   *asyncQueueControl;
static SlruCtlData			AsyncCtlData;

#define AsyncCtl					(&AsyncCtlData)
#define QUEUE_PAGESIZE				BLCKSZ
#define QUEUE_FULL_WARN_INTERVAL	5000	/* warn at most once every 5s */

/*
 * slru.c currently assumes that all filenames are four characters of hex
 * digits. That means that we can use segments 0000 through FFFF.
 * Each segment contains SLRU_PAGES_PER_SEGMENT pages which gives us
 * the pages from 0 to SLRU_PAGES_PER_SEGMENT * 0xFFFF.
 *
 * It's of course easy to enhance slru.c but those pages give us so much
 * space already that it doesn't seem worth the trouble...
 *
 * It's a legal test case to define QUEUE_MAX_PAGE to a very small multiply of
 * SLRU_PAGES_PER_SEGMENT to test queue full behaviour.
 */
#define QUEUE_MAX_PAGE			(SLRU_PAGES_PER_SEGMENT * 0xFFFF)

static List *pendingNotifies = NIL;				/* list of Notifications */
static List *upperPendingNotifies = NIL;		/* list of upper-xact lists */
static List *listenChannels = NIL;	/* list of channels we are listening to */

/*
 * State for inbound notifications consists of two flags: one saying whether
 * the signal handler is currently allowed to call ProcessIncomingNotify
 * directly, and one saying whether the signal has occurred but the handler
 * was not allowed to call ProcessIncomingNotify at the time.
 *
 * NB: the "volatile" on these declarations is critical!  If your compiler
 * does not grok "volatile", you'd be best advised to compile this file
 * with all optimization turned off.
 */
static volatile sig_atomic_t notifyInterruptEnabled = 0;
static volatile sig_atomic_t notifyInterruptOccurred = 0;

/* True if we've registered an on_shmem_exit cleanup */
static bool unlistenExitRegistered = false;

bool		Trace_notify = false;

static void queue_listen(ListenActionKind action, const char *condname);
static void Async_UnlistenOnExit(int code, Datum arg);
static bool IsListeningOn(const char *channel);
static bool AsyncExistsPendingNotify(const char *channel, const char *payload);
static void Exec_Listen(const char *channel);
static void Exec_Unlisten(const char *channel);
static void Exec_UnlistenAll(void);
static void SignalBackends(SignalType type);
static void Send_Notify(void);
static bool asyncQueuePagePrecedesPhysically(int p, int q);
static bool asyncQueuePagePrecedesLogically(int p, int q, int head);
static bool asyncQueueAdvance(QueuePosition *position, int entryLength);
static void asyncQueueNotificationToEntry(Notification *n, AsyncQueueEntry *qe);
static void asyncQueueEntryToNotification(AsyncQueueEntry *qe, Notification *n);
static List *asyncQueueAddEntries(List *notifications);
static bool asyncQueueGetEntriesByPage(QueuePosition *current,
									   QueuePosition stop,
									   List **committed,
									   MemoryContext committedContext,
									   List **uncommitted,
									   MemoryContext uncommittedContext);
static void asyncQueueReadAllNotifications(QueueProcessType type);
static void asyncQueueAdvanceTail(void);
static void ProcessIncomingNotify(void);
static void NotifyMyFrontEnd(const char *channel,
							 const char *payload,
							 int32 dstPid);
static bool AsyncExistsPendingNotify(const char *channel, const char *payload);
static void ClearPendingActionsAndNotifies(void);

/*
 * We will work on the page range of 0..(SLRU_PAGES_PER_SEGMENT * 0xFFFF).
 * asyncQueuePagePrecedesPhysically just checks numerically without any magic if
 * one page precedes another one.
 *
 * On the other hand, when asyncQueuePagePrecedesLogically does that check, it
 * takes the current head page number into account. Now if we have wrapped
 * around, it can happen that p precedes q, even though p > q (if the head page
 * is in between the two).
 */ 
static bool
asyncQueuePagePrecedesPhysically(int p, int q)
{
	return p < q;
}

static bool
asyncQueuePagePrecedesLogically(int p, int q, int head)
{
	if (p <= head && q <= head)
		return p < q;
	if (p > head && q > head)
		return p < q;
	if (p <= head)
	{
		Assert(q > head);
		/* q is older */
		return false;
	}
	else
	{
		Assert(p > head && q <= head);
		/* p is older */
		return true;
	}
}

void
AsyncShmemInit(void)
{
	bool	found;
	int		slotno;
	Size	size;

	/*
	 * Remember that sizeof(AsyncQueueControl) already contains one member of
	 * QueueBackendStatus, so we only need to add the status space requirement
	 * for MaxBackends-1 backends.
	 */
	size = mul_size(MaxBackends-1, sizeof(QueueBackendStatus));
	size = add_size(size, sizeof(AsyncQueueControl));

	asyncQueueControl = (AsyncQueueControl *)
		ShmemInitStruct("Async Queue Control", size, &found);

	if (!asyncQueueControl)
		elog(ERROR, "out of memory");

	if (!found)
	{
		int		i;
		SET_QUEUE_POS(QUEUE_HEAD, 0, 0);
		SET_QUEUE_POS(QUEUE_TAIL, QUEUE_MAX_PAGE, 0);
		for (i = 0; i < MaxBackends; i++)
		{
			SET_QUEUE_POS(QUEUE_BACKEND_POS(i), 0, 0);
			QUEUE_BACKEND_PID(i) = InvalidPid;
		}
	}

	AsyncCtl->PagePrecedes = asyncQueuePagePrecedesPhysically;
	SimpleLruInit(AsyncCtl, "Async Ctl", NUM_ASYNC_BUFFERS, 0,
				  AsyncCtlLock, "pg_notify");
	AsyncCtl->do_fsync = false;
	asyncQueueControl->lastQueueFullWarn = GetCurrentTimestamp();

	if (!found)
	{
		LWLockAcquire(AsyncQueueLock, LW_EXCLUSIVE);
		LWLockAcquire(AsyncCtlLock, LW_EXCLUSIVE);
		slotno = SimpleLruZeroPage(AsyncCtl, QUEUE_POS_PAGE(QUEUE_HEAD));
		AsyncCtl->shared->page_dirty[slotno] = true;
		SimpleLruWritePage(AsyncCtl, slotno, NULL);
		LWLockRelease(AsyncCtlLock);
		LWLockRelease(AsyncQueueLock);

		SlruScanDirectory(AsyncCtl, QUEUE_MAX_PAGE, true);
	}
}


/*
 * Async_Notify
 *
 *		This is executed by the SQL notify command.
 *
 *		Adds the channel to the list of pending notifies.
 *		Actual notification happens during transaction commit.
 *		^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 */
void
Async_Notify(const char *channel, const char *payload)
{

	if (Trace_notify)
		elog(DEBUG1, "Async_Notify(%s)", channel);

	/*
	 * XXX - do we now need a guc parameter max_notifies_per_txn?
	 */ 

	/* no point in making duplicate entries in the list ... */
	if (!AsyncExistsPendingNotify(channel, payload))
	{
		Notification *n;
		/*
		 * The name list needs to live until end of transaction, so store it
		 * in the transaction context.
		 */
		MemoryContext oldcontext;

		oldcontext = MemoryContextSwitchTo(CurTransactionContext);

		n = (Notification *) palloc(sizeof(Notification));
		/* will set the xid later... */
		n->xid = InvalidTransactionId;
		n->channel = pstrdup(channel);
		if (payload)
			n->payload = pstrdup(payload);
		else
			n->payload = "";
		n->dstPid = InvalidPid;

		/*
		 * We want to preserve the order so we need to append every
		 * notification. See comments at AsyncExistsPendingNotify().
		 */
		pendingNotifies = lappend(pendingNotifies, n);

		MemoryContextSwitchTo(oldcontext);
	}
}

/*
 * queue_listen
 *		Common code for listen, unlisten, unlisten all commands.
 *
 *		Adds the request to the list of pending actions.
 *		Actual update of pg_listener happens during transaction commit.
 *		^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 */
static void
queue_listen(ListenActionKind action, const char *condname)
{
	MemoryContext oldcontext;
	ListenAction *actrec;

	/*
	 * Unlike Async_Notify, we don't try to collapse out duplicates. It would
	 * be too complicated to ensure we get the right interactions of
	 * conflicting LISTEN/UNLISTEN/UNLISTEN_ALL, and it's unlikely that there
	 * would be any performance benefit anyway in sane applications.
	 */
	oldcontext = MemoryContextSwitchTo(CurTransactionContext);

	/* space for terminating null is included in sizeof(ListenAction) */
	actrec = (ListenAction *) palloc(sizeof(ListenAction) + strlen(condname));
	actrec->action = action;
	strcpy(actrec->condname, condname);

	pendingActions = lappend(pendingActions, actrec);

	MemoryContextSwitchTo(oldcontext);
}

/*
 * Async_Listen
 *
 *		This is executed by the SQL listen command.
 */
void
Async_Listen(const char *channel)
{
	if (Trace_notify)
		elog(DEBUG1, "Async_Listen(%s,%d)", channel, MyProcPid);

	queue_listen(LISTEN_LISTEN, channel);
}

/*
 * Async_Unlisten
 *
 *		This is executed by the SQL unlisten command.
 */
void
Async_Unlisten(const char *channel)
{
	if (Trace_notify)
		elog(DEBUG1, "Async_Unlisten(%s,%d)", channel, MyProcPid);

	/* If we couldn't possibly be listening, no need to queue anything */
	if (pendingActions == NIL && !unlistenExitRegistered)
		return;

	queue_listen(LISTEN_UNLISTEN, channel);
}

/*
 * Async_UnlistenAll
 *
 *		This is invoked by UNLISTEN * command, and also at backend exit.
 */
void
Async_UnlistenAll(void)
{
	if (Trace_notify)
		elog(DEBUG1, "Async_UnlistenAll(%d)", MyProcPid);

	/* If we couldn't possibly be listening, no need to queue anything */
	if (pendingActions == NIL && !unlistenExitRegistered)
		return;

	queue_listen(LISTEN_UNLISTEN_ALL, "");
}

/*
 * Async_UnlistenOnExit
 *
 *		This is executed if we have done any LISTENs in this backend.
 *		It might not be necessary anymore, if the user UNLISTENed everything,
 *		but we don't try to detect that case.
 */
static void
Async_UnlistenOnExit(int code, Datum arg)
{
	AbortOutOfAnyTransaction();
	Exec_UnlistenAll();
}

/*
 * AtPrepare_Notify
 *
 *		This is called at the prepare phase of a two-phase
 *		transaction.  Save the state for possible commit later.
 */
void
AtPrepare_Notify(void)
{
	ListCell   *p;

	/* It's not sensible to have any pending LISTEN/UNLISTEN actions */
	if (pendingActions)
		ereport(ERROR,
				(errcode(ERRCODE_FEATURE_NOT_SUPPORTED),
				 errmsg("cannot PREPARE a transaction that has executed LISTEN or UNLISTEN")));

	/* We can deal with pending NOTIFY though */
	foreach(p, pendingNotifies)
	{
		AsyncQueueEntry qe;
		Notification   *n;

		n = (Notification *) lfirst(p);

		asyncQueueNotificationToEntry(n, &qe);

		RegisterTwoPhaseRecord(TWOPHASE_RM_NOTIFY_ID, 0,
							   &qe, qe.length);
	}

	/*
	 * We can clear the state immediately, rather than needing a separate
	 * PostPrepare call, because if the transaction fails we'd just discard
	 * the state anyway.
	 */
	ClearPendingActionsAndNotifies();
}

/*
 * AtCommit_NotifyBeforeCommit
 *
 *		This is called at transaction commit, before actually committing to
 *		clog.
 *
 *		If there are pending LISTEN/UNLISTEN actions, update our
 *		"listenChannels" list.
 *
 *		If there are outbound notify requests in the pendingNotifies list, add
 *		them to the global queue and signal any backend that is listening.
 */
void
AtCommit_NotifyBeforeCommit(void)
{
	ListCell   *p;

	needSignalBackends = false;

	if (pendingActions == NIL && pendingNotifies == NIL)
		return;					/* no relevant statements in this xact */

	/*
	 * NOTIFY is disabled if not normal processing mode. This test used to be
	 * in xact.c, but it seems cleaner to do it here.
	 */
	if (!IsNormalProcessingMode())
	{
		ClearPendingActionsAndNotifies();
		return;
	}

	if (Trace_notify)
		elog(DEBUG1, "AtCommit_NotifyBeforeCommit");

	/* Perform any pending listen/unlisten actions */
	foreach(p, pendingActions)
	{
		ListenAction *actrec = (ListenAction *) lfirst(p);

		switch (actrec->action)
		{
			case LISTEN_LISTEN:
				Exec_Listen(actrec->condname);
				break;
			case LISTEN_UNLISTEN:
				Exec_Unlisten(actrec->condname);
				break;
			case LISTEN_UNLISTEN_ALL:
				Exec_UnlistenAll();
				break;
		}
	}

	/*
	 * Perform any pending notifies.
	 */
	if (pendingNotifies)
	{
		needSignalBackends = true;
		Send_Notify();
	}
}

/*
 * AtCommit_NotifyAfterCommit
 *
 *		This is called at transaction commit, after committing to clog.
 *
 *		Notify the listening backends.
 */
void
AtCommit_NotifyAfterCommit(void)
{
	if (needSignalBackends)
		SignalBackends(SIGNAL_ALL);

	ClearPendingActionsAndNotifies();

	if (Trace_notify)
		elog(DEBUG1, "AtCommit_NotifyAfterCommit: done");
}

/*
 * This function is executed for every notification found in the queue in order
 * to check if the current backend is listening on that channel. Not sure if we
 * should further optimize this, for example convert to a sorted array and
 * allow binary search on it...
 */
static bool
IsListeningOn(const char *channel)
{
	ListCell   *p;

	foreach(p, listenChannels)
	{
		char *lchan = (char *) lfirst(p);
		if (strcmp(lchan, channel) == 0)
			/* already listening on this channel */
			return true;
	}
	return false;
}


/*
 * Exec_Listen --- subroutine for AtCommit_Notify
 *
 *		Register the current backend as listening on the specified channel.
 */
static void
Exec_Listen(const char *channel)
{
	MemoryContext oldcontext;

	if (Trace_notify)
		elog(DEBUG1, "Exec_Listen(%s,%d)", channel, MyProcPid);

	/* Detect whether we are already listening on this channel */
	if (IsListeningOn(channel))
		return;

	/*
	 * OK to insert to the list.
	 */
	if (listenChannels == NIL)
	{
		/*
		 * This is our first LISTEN, establish our pointer.
		 */
		LWLockAcquire(AsyncQueueLock, LW_SHARED);
		QUEUE_BACKEND_POS(MyBackendId) = QUEUE_HEAD;
		QUEUE_BACKEND_PID(MyBackendId) = MyProcPid;
		LWLockRelease(AsyncQueueLock);
		/*
		 * Actually this is only necessary if we are the first listener
		 * (The tail pointer needs to be identical with the pointer of at
		 * least one backend).
		 */
		asyncQueueAdvanceTail();
	}

	oldcontext = MemoryContextSwitchTo(TopMemoryContext);
	listenChannels = lappend(listenChannels, pstrdup(channel));
	MemoryContextSwitchTo(oldcontext);

	/*
	 * Now that we are listening, make sure we will unlisten before dying.
	 */
	if (!unlistenExitRegistered)
	{
		on_shmem_exit(Async_UnlistenOnExit, 0);
		unlistenExitRegistered = true;
	}
}

/*
 * Exec_Unlisten --- subroutine for AtCommit_Notify
 *
 *		Remove a specified channel from "listenChannel".
 */
static void
Exec_Unlisten(const char *channel)
{
	ListCell   *p;
	ListCell   *prev = NULL;

	if (Trace_notify)
		elog(DEBUG1, "Exec_Unlisten(%s,%d)", channel, MyProcPid);

	/* Detect whether we are already listening on this channel */
	foreach(p, listenChannels)
	{
		char *lchan = (char *) lfirst(p);
		if (strcmp(lchan, channel) == 0)
		{
			/*
			 * Since the list is living in the TopMemoryContext, we free
			 * the memory. The ListCell is freed by list_delete_cell().
			 */
			pfree(lchan);
			listenChannels = list_delete_cell(listenChannels, p, prev);
			if (listenChannels == NIL)
			{
				bool advanceTail = false;
				/*
				 * This backend is not listening anymore.
				 */
				LWLockAcquire(AsyncQueueLock, LW_SHARED);
				QUEUE_BACKEND_PID(MyBackendId) = InvalidPid;

				/*
				 * If we have been the last backend, advance the tail pointer.
				 */
				if (QUEUE_POS_EQUAL(QUEUE_BACKEND_POS(MyBackendId), QUEUE_TAIL))
					advanceTail = true;
				LWLockRelease(AsyncQueueLock);

				if (advanceTail)
					asyncQueueAdvanceTail();
			}
			return;
		}
		prev = p;
	}
	
	/*
	 * We do not complain about unlistening something not being listened;
	 * should we?
	 */
}

/*
 * Exec_UnlistenAll --- subroutine for AtCommit_Notify
 *
 *		Unlisten on all channels for this backend.
 */
static void
Exec_UnlistenAll(void)
{
	bool advanceTail = false;

	if (Trace_notify)
		elog(DEBUG1, "Exec_UnlistenAll(%d)", MyProcPid);

	LWLockAcquire(AsyncQueueLock, LW_SHARED);
	QUEUE_BACKEND_PID(MyBackendId) = InvalidPid;

	/*
	 * Since the list is living in the TopMemoryContext, we free the memory.
	 */
	list_free_deep(listenChannels);
	listenChannels = NIL;

	/*
	 * If we have been the last backend, advance the tail pointer.
	 */
	if (QUEUE_POS_EQUAL(QUEUE_BACKEND_POS(MyBackendId), QUEUE_TAIL))
		advanceTail = true;
	LWLockRelease(AsyncQueueLock);

	if (advanceTail)
		asyncQueueAdvanceTail();
}

static bool
asyncQueueIsFull()
{
	QueuePosition	lookahead = QUEUE_HEAD;
	Size remain = QUEUE_PAGESIZE - QUEUE_POS_OFFSET(lookahead) - 1;
	Size advance = Min(remain, NOTIFY_PAYLOAD_MAX_LENGTH);

	/*
	 * Check what happens if we wrote a maximally sized entry. Would we go to a
	 * new page? If not, then our queue can not be full (because we can still
	 * fill at least the current page with at least one more entry).
	 */
	if (!asyncQueueAdvance(&lookahead, advance))
		return false;

	/*
	 * The queue is full if with a switch to a new page we reach the page
	 * of the tail pointer.
	 */
	return QUEUE_POS_PAGE(lookahead) == QUEUE_POS_PAGE(QUEUE_TAIL);
}

/*
 * The function advances the position to the next entry. In case we jump to
 * a new page the function returns true, else false.
 */
static bool
asyncQueueAdvance(QueuePosition *position, int entryLength)
{
	int		pageno = QUEUE_POS_PAGE(*position);
	int		offset = QUEUE_POS_OFFSET(*position);
	bool	pageJump = false;

	/*
	 * Move to the next writing position: First jump over what we have just
	 * written or read.
	 */
	offset += entryLength;
	Assert(offset < QUEUE_PAGESIZE);

	/*
	 * In a second step check if another entry can be written to the page. If
	 * it does, stay here, we have reached the next position. If not, then we
	 * need to move on to the next page.
	 */
	if (offset + AsyncQueueEntryEmptySize >= QUEUE_PAGESIZE)
	{
		pageno++;
		if (pageno > QUEUE_MAX_PAGE)
			/* wrap around */
			pageno = 0;
		offset = 0;
		pageJump = true;
	}

	SET_QUEUE_POS(*position, pageno, offset);
	return pageJump;
}

static void
asyncQueueNotificationToEntry(Notification *n, AsyncQueueEntry *qe)
{
		Assert(n->channel);
		Assert(n->payload);
		Assert(strlen(n->payload) <= NOTIFY_PAYLOAD_MAX_LENGTH);

		/* The terminator is already included in AsyncQueueEntryEmptySize */
		qe->length = AsyncQueueEntryEmptySize + strlen(n->payload);
		qe->srcPid = MyProcPid;
		qe->dboid = MyDatabaseId;
		qe->xid = GetCurrentTransactionId();
		strcpy(qe->channel, n->channel);
		strcpy(qe->payload, n->payload);
}

static void
asyncQueueEntryToNotification(AsyncQueueEntry *qe, Notification *n)
{
	n->channel = pstrdup(qe->channel);
	n->payload = pstrdup(qe->payload);
	n->srcPid = qe->srcPid;
	n->xid = qe->xid;
}

static List *
asyncQueueAddEntries(List *notifications)
{
	int				pageno;
	int				offset;
	int				slotno;
	AsyncQueueEntry	qe;

	/*
	 * Note that we are holding exclusive AsyncQueueLock already.
	 */
	LWLockAcquire(AsyncCtlLock, LW_EXCLUSIVE);
	pageno = QUEUE_POS_PAGE(QUEUE_HEAD);
	slotno = SimpleLruReadPage(AsyncCtl, pageno, true, InvalidTransactionId);
	AsyncCtl->shared->page_dirty[slotno] = true;

	do
	{
		Notification   *n;

		if (asyncQueueIsFull())
		{
			/* document that we will not go into the if command further down */
			Assert(QUEUE_POS_OFFSET(QUEUE_HEAD) != 0);
			break;
		}

		n = (Notification *) linitial(notifications);

		asyncQueueNotificationToEntry(n, &qe);

		offset = QUEUE_POS_OFFSET(QUEUE_HEAD);
		/*
		 * Check whether or not the entry still fits on the current page.
		 */
		if (offset + qe.length < QUEUE_PAGESIZE)
		{
			notifications = list_delete_first(notifications);
		}
		else
		{
			/*
			 * Write a dummy entry to fill up the page. Actually readers will
			 * only check dboid and since it won't match any reader's database
			 * oid, they will ignore this entry and move on.
			 */
			qe.length = QUEUE_PAGESIZE - offset - 1;
			qe.dboid = InvalidOid;
			qe.channel[0] = '\0';
			qe.payload[0] = '\0';
			qe.xid = InvalidTransactionId;
		}
		memcpy((char*) AsyncCtl->shared->page_buffer[slotno] + offset,
			   &qe, qe.length);

	} while (!asyncQueueAdvance(&(QUEUE_HEAD), qe.length)
			 && notifications != NIL);

	if (QUEUE_POS_OFFSET(QUEUE_HEAD) == 0)
	{
		/*
		 * If the next entry needs to go to a new page, prepare that page
		 * already.
		 */
		slotno = SimpleLruZeroPage(AsyncCtl, QUEUE_POS_PAGE(QUEUE_HEAD));
		AsyncCtl->shared->page_dirty[slotno] = true;
	}
	LWLockRelease(AsyncCtlLock);

	return notifications;
}

static void
asyncQueueFullWarning()
{
	/*
	 * Caller must hold exclusive AsyncQueueLock.
	 */
	TimestampTz		t = GetCurrentTimestamp();
	QueuePosition	min = QUEUE_HEAD;
	int32			minPid = InvalidPid;
	int				i;

	for (i = 0; i < MaxBackends; i++)
		if (QUEUE_BACKEND_PID(i) != InvalidPid)
		{
			min = QUEUE_POS_MIN(min, QUEUE_BACKEND_POS(i), QUEUE_HEAD);
			if (QUEUE_POS_EQUAL(min, QUEUE_BACKEND_POS(i)))
				minPid = QUEUE_BACKEND_PID(i);
		}

	if (TimestampDifferenceExceeds(asyncQueueControl->lastQueueFullWarn,
								   t, QUEUE_FULL_WARN_INTERVAL))
	{
		ereport(WARNING, (errmsg("pg_notify queue is full. "
								 "Among the slowest backends: %d", minPid)));
		asyncQueueControl->lastQueueFullWarn = t;
	}
}

/*
 * Send_Notify --- subroutine for AtCommit_Notify
 *
 * Add the pending notifications to the queue and signal the listening
 * backends.
 *
 * A full queue is very uncommon and should really not happen, given that we
 * have so much space available in our slru pages. Nevertheless we need to
 * deal with this possibility. Note that when we get here we are in the process
 * of committing our transaction, we have not yet committed to clog but this
 * would be the next step.
 */
static void
Send_Notify()
{
	while (pendingNotifies != NIL)
	{
		LWLockAcquire(AsyncQueueLock, LW_EXCLUSIVE);
		while (asyncQueueIsFull())
		{
			asyncQueueFullWarning();
			LWLockRelease(AsyncQueueLock);

			/* check if our query is cancelled */
			CHECK_FOR_INTERRUPTS();

			SignalBackends(SIGNAL_SLOW);

			asyncQueueReadAllNotifications(READ_ALL_TO_UNCOMMITTED);

			asyncQueueAdvanceTail();
			pg_usleep(100 * 1000L); /* 1ms */
			LWLockAcquire(AsyncQueueLock, LW_EXCLUSIVE);
		}
		Assert(pendingNotifies != NIL);
		pendingNotifies = asyncQueueAddEntries(pendingNotifies);
		LWLockRelease(AsyncQueueLock);
	}
}

/*
 * Send signals to all listening backends. It would be easy here to check
 * for backends that are already up-to-date, i.e.
 *
 *   QUEUE_BACKEND_POS(pid) == QUEUE_HEAD
 *
 * but in general we need to signal them anyway. If we didn't, we would not
 * have the guarantee that they can deliver their notifications from
 * uncommittedNotifications. Only when the queue is full and we signal the
 * backends to read also uncommitted data, we can use this optimization.
 *
 * Since we know the BackendId and the Pid the signalling is quite cheap.
 */
static void
SignalBackends(SignalType type)
{
	ListCell	   *p1, *p2;
	int				i;
	int32			pid;
	List		   *pids = NIL;
	List		   *ids = NIL;

	/* Signal everybody who is LISTENing to any channel. */
	LWLockAcquire(AsyncQueueLock, LW_EXCLUSIVE);
	for (i = 0; i < MaxBackends; i++)
	{
		pid = QUEUE_BACKEND_PID(i);
		if (pid != InvalidPid)
		{
			if (type == SIGNAL_SLOW &&
					QUEUE_POS_EQUAL(QUEUE_BACKEND_POS(i), QUEUE_HEAD))
				continue;
			pids = lappend_int(pids, pid);
			ids = lappend_int(ids, i);
		}
	}
	LWLockRelease(AsyncQueueLock);
	
	forboth(p1, pids, p2, ids)
	{
		pid = (int32) lfirst_int(p1);
		i = lfirst_int(p2);
		/*
		 * Should we check for failure? Can it happen that a backend
		 * has crashed without the postmaster starting over?
		 */
		if (SendProcSignal(pid, PROCSIG_NOTIFY_INTERRUPT, i) < 0)
			elog(WARNING, "Error signalling backend %d", pid);
	}
}

/*
 * AtAbort_Notify
 *
 *		This is called at transaction abort.
 *
 *		Gets rid of pending actions and outbound notifies that we would have
 *		executed if the transaction got committed.
 */
void
AtAbort_Notify(void)
{
	ClearPendingActionsAndNotifies();
}

/*
 * AtSubStart_Notify() --- Take care of subtransaction start.
 *
 * Push empty state for the new subtransaction.
 */
void
AtSubStart_Notify(void)
{
	MemoryContext old_cxt;

	/* Keep the list-of-lists in TopTransactionContext for simplicity */
	old_cxt = MemoryContextSwitchTo(TopTransactionContext);

	upperPendingActions = lcons(pendingActions, upperPendingActions);

	Assert(list_length(upperPendingActions) ==
		   GetCurrentTransactionNestLevel() - 1);

	pendingActions = NIL;

	upperPendingNotifies = lcons(pendingNotifies, upperPendingNotifies);

	Assert(list_length(upperPendingNotifies) ==
		   GetCurrentTransactionNestLevel() - 1);

	pendingNotifies = NIL;

	MemoryContextSwitchTo(old_cxt);
}

/*
 * AtSubCommit_Notify() --- Take care of subtransaction commit.
 *
 * Reassign all items in the pending lists to the parent transaction.
 */
void
AtSubCommit_Notify(void)
{
	List	   *parentPendingActions;
	List	   *parentPendingNotifies;

	parentPendingActions = (List *) linitial(upperPendingActions);
	upperPendingActions = list_delete_first(upperPendingActions);

	Assert(list_length(upperPendingActions) ==
		   GetCurrentTransactionNestLevel() - 2);

	/*
	 * Mustn't try to eliminate duplicates here --- see queue_listen()
	 */
	pendingActions = list_concat(parentPendingActions, pendingActions);

	parentPendingNotifies = (List *) linitial(upperPendingNotifies);
	upperPendingNotifies = list_delete_first(upperPendingNotifies);

	Assert(list_length(upperPendingNotifies) ==
		   GetCurrentTransactionNestLevel() - 2);

	/*
	 * We could try to eliminate duplicates here, but it seems not worthwhile.
	 */
	pendingNotifies = list_concat(parentPendingNotifies, pendingNotifies);
}

/*
 * AtSubAbort_Notify() --- Take care of subtransaction abort.
 */
void
AtSubAbort_Notify(void)
{
	int			my_level = GetCurrentTransactionNestLevel();

	/*
	 * All we have to do is pop the stack --- the actions/notifies made in
	 * this subxact are no longer interesting, and the space will be freed
	 * when CurTransactionContext is recycled.
	 *
	 * This routine could be called more than once at a given nesting level if
	 * there is trouble during subxact abort.  Avoid dumping core by using
	 * GetCurrentTransactionNestLevel as the indicator of how far we need to
	 * prune the list.
	 */
	while (list_length(upperPendingActions) > my_level - 2)
	{
		pendingActions = (List *) linitial(upperPendingActions);
		upperPendingActions = list_delete_first(upperPendingActions);
	}

	while (list_length(upperPendingNotifies) > my_level - 2)
	{
		pendingNotifies = (List *) linitial(upperPendingNotifies);
		upperPendingNotifies = list_delete_first(upperPendingNotifies);
	}
}

/*
 * HandleNotifyInterrupt
 *
 *		This is called when PROCSIG_NOTIFY_INTERRUPT is received.
 *
 *		If we are idle (notifyInterruptEnabled is set), we can safely invoke
 *		ProcessIncomingNotify directly.  Otherwise, just set a flag
 *		to do it later.
 */
void
HandleNotifyInterrupt(void)
{
	/*
	 * Note: this is called by a SIGNAL HANDLER. You must be very wary what
	 * you do here. Some helpful soul had this routine sprinkled with
	 * TPRINTFs, which would likely lead to corruption of stdio buffers if
	 * they were ever turned on.
	 */

	/* Don't joggle the elbow of proc_exit */
	if (proc_exit_inprogress)
		return;

	if (notifyInterruptEnabled)
	{
		bool		save_ImmediateInterruptOK = ImmediateInterruptOK;

		/*
		 * We may be called while ImmediateInterruptOK is true; turn it off
		 * while messing with the NOTIFY state.  (We would have to save and
		 * restore it anyway, because PGSemaphore operations inside
		 * ProcessIncomingNotify() might reset it.)
		 */
		ImmediateInterruptOK = false;

		/*
		 * I'm not sure whether some flavors of Unix might allow another
		 * SIGUSR1 occurrence to recursively interrupt this routine. To cope
		 * with the possibility, we do the same sort of dance that
		 * EnableNotifyInterrupt must do --- see that routine for comments.
		 */
		notifyInterruptEnabled = 0;		/* disable any recursive signal */
		notifyInterruptOccurred = 1;	/* do at least one iteration */
		for (;;)
		{
			notifyInterruptEnabled = 1;
			if (!notifyInterruptOccurred)
				break;
			notifyInterruptEnabled = 0;
			if (notifyInterruptOccurred)
			{
				/* Here, it is finally safe to do stuff. */
				if (Trace_notify)
					elog(DEBUG1, "HandleNotifyInterrupt: perform async notify");

				ProcessIncomingNotify();

				if (Trace_notify)
					elog(DEBUG1, "HandleNotifyInterrupt: done");
			}
		}

		/*
		 * Restore ImmediateInterruptOK, and check for interrupts if needed.
		 */
		ImmediateInterruptOK = save_ImmediateInterruptOK;
		if (save_ImmediateInterruptOK)
			CHECK_FOR_INTERRUPTS();
	}
	else
	{
		/*
		 * In this path it is NOT SAFE to do much of anything, except this:
		 */
		notifyInterruptOccurred = 1;
	}
}

/*
 * EnableNotifyInterrupt
 *
 *		This is called by the PostgresMain main loop just before waiting
 *		for a frontend command.  If we are truly idle (ie, *not* inside
 *		a transaction block), then process any pending inbound notifies,
 *		and enable the signal handler to process future notifies directly.
 *
 *		NOTE: the signal handler starts out disabled, and stays so until
 *		PostgresMain calls this the first time.
 */
void
EnableNotifyInterrupt(void)
{
	if (IsTransactionOrTransactionBlock())
		return;					/* not really idle */

	/*
	 * This code is tricky because we are communicating with a signal handler
	 * that could interrupt us at any point.  If we just checked
	 * notifyInterruptOccurred and then set notifyInterruptEnabled, we could
	 * fail to respond promptly to a signal that happens in between those two
	 * steps.  (A very small time window, perhaps, but Murphy's Law says you
	 * can hit it...)  Instead, we first set the enable flag, then test the
	 * occurred flag.  If we see an unserviced interrupt has occurred, we
	 * re-clear the enable flag before going off to do the service work. (That
	 * prevents re-entrant invocation of ProcessIncomingNotify() if another
	 * interrupt occurs.) If an interrupt comes in between the setting and
	 * clearing of notifyInterruptEnabled, then it will have done the service
	 * work and left notifyInterruptOccurred zero, so we have to check again
	 * after clearing enable.  The whole thing has to be in a loop in case
	 * another interrupt occurs while we're servicing the first. Once we get
	 * out of the loop, enable is set and we know there is no unserviced
	 * interrupt.
	 *
	 * NB: an overenthusiastic optimizing compiler could easily break this
	 * code. Hopefully, they all understand what "volatile" means these days.
	 */
	for (;;)
	{
		notifyInterruptEnabled = 1;
		if (!notifyInterruptOccurred)
			break;
		notifyInterruptEnabled = 0;
		if (notifyInterruptOccurred)
		{
			if (Trace_notify)
				elog(DEBUG1, "EnableNotifyInterrupt: perform async notify");

			ProcessIncomingNotify();

			if (Trace_notify)
				elog(DEBUG1, "EnableNotifyInterrupt: done");
		}
	}
}

/*
 * DisableNotifyInterrupt
 *
 *		This is called by the PostgresMain main loop just after receiving
 *		a frontend command.  Signal handler execution of inbound notifies
 *		is disabled until the next EnableNotifyInterrupt call.
 *
 *		The PROCSIG_CATCHUP_INTERRUPT signal handler also needs to call this,
 *		so as to prevent conflicts if one signal interrupts the other.  So we
 *		must return the previous state of the flag.
 */
bool
DisableNotifyInterrupt(void)
{
	bool		result = (notifyInterruptEnabled != 0);

	notifyInterruptEnabled = 0;

	return result;
}

/*
 * This function will ask for a page with ReadOnly access and once we have the
 * lock, we read the whole content and pass back two lists of notifications
 * that the calling function will deliver then. The first list will contain all
 * notifications from transactions that have already committed and the second
 * one will contain uncommitted notifications.
 *
 * We stop if we have either reached the stop position or go to a new page.
 *
 * If we have reached the end (i.e. it does not make sense to call this
 * function again), else false.
 */
static bool
asyncQueueGetEntriesByPage(QueuePosition *current, QueuePosition stop,
						   List **committed, MemoryContext committedContext,
						   List **uncommitted, MemoryContext uncommittedContext)
{
	int				slotno;
	AsyncQueueEntry	qe;
	Notification   *n;
	bool			reachedStop = false;

	if (QUEUE_POS_EQUAL(*current, stop))
		return true;

	slotno = SimpleLruReadPage_ReadOnly(AsyncCtl, current->page,
										InvalidTransactionId);
	do {
		char *readPtr = (char *) (AsyncCtl->shared->page_buffer[slotno]);
		readPtr += current->offset;

		if (QUEUE_POS_EQUAL(*current, stop))
		{
			reachedStop = true;
			break;
		}

		memcpy(&qe, readPtr, AsyncQueueEntryEmptySize);

		if (qe.dboid == MyDatabaseId)
		{
			MemoryContext	oldcontext;

			if (TransactionIdDidCommit(qe.xid))
			{
				Assert(committed != NULL);
				if (IsListeningOn(qe.channel))
				{
					if (qe.length > AsyncQueueEntryEmptySize)
						memcpy(&qe, readPtr, qe.length);
					oldcontext = MemoryContextSwitchTo(committedContext);
					n = (Notification *) palloc(sizeof(Notification));
					asyncQueueEntryToNotification(&qe, n);
					*committed = lappend(*committed, n);
					MemoryContextSwitchTo(oldcontext);
				}
			}
			else
			{
				if (!TransactionIdDidAbort(qe.xid))
				{
					/*
					 * We have found a transaction that has not committed.
					 * Should we read uncommitted data or not ?
					 */
					if (!uncommitted)
					{
						reachedStop = true;
						break;
					}
					if (qe.length > AsyncQueueEntryEmptySize)
						memcpy(&qe, readPtr, qe.length);
					oldcontext = MemoryContextSwitchTo(uncommittedContext);
					n = (Notification *) palloc(sizeof(Notification));
					asyncQueueEntryToNotification(&qe, n);
					*uncommitted= lappend(*uncommitted, n);
					MemoryContextSwitchTo(oldcontext);
				}
			}
		}
		/*
		 * The call to asyncQueueAdvance just jumps over what we have
		 * just read. If there is no more space for the next record on the
		 * current page, it will also switch to the beginning of the next page.
		 */
	} while(!asyncQueueAdvance(current, qe.length));

	LWLockRelease(AsyncCtlLock);

	if (QUEUE_POS_EQUAL(*current, stop))
		reachedStop = true;

	return reachedStop;
}

static void
asyncQueueReadAllNotifications(QueueProcessType type)
{
	QueuePosition	pos;
	QueuePosition	oldpos;
	QueuePosition	head;
	List		   *notifications;
	ListCell	   *lc;
	Notification   *n;
	bool			advanceTail = false;
	bool			reachedStop;

	LWLockAcquire(AsyncQueueLock, LW_SHARED);
	pos = oldpos = QUEUE_BACKEND_POS(MyBackendId);
	head = QUEUE_HEAD;
	LWLockRelease(AsyncQueueLock);

	/* Nothing to do, we have read all notifications already. */
	if (QUEUE_POS_EQUAL(pos, head))
		return;

	do 
	{
		/*
		 * Our stop position is what we found to be the head's position when
		 * we entered this function. It might have changed already. But if it
		 * has, we will receive (or have already received and queued) another
		 * signal and come here again.
		 *
		 * We are not holding AsyncQueueLock here! The queue can only extend
		 * beyond the head pointer (see above) and we leave our backend's
		 * pointer where it is so nobody will truncate or rewrite pages under
		 * us.
		 */
		reachedStop = false;

		if (type == READ_ALL_TO_UNCOMMITTED)
			/*
			 * If the queue is full, we call this in the writing backend.
			 * if a backend sends more notifications than the queue can hold
			 * it also needs to read its own notifications from time to time
			 * such that it can reuse the space of the queue.
			 */
			reachedStop = asyncQueueGetEntriesByPage(&pos, head,
								   &uncommittedNotifications, TopMemoryContext,
								   &uncommittedNotifications, TopMemoryContext);
		else
		{
			/*
			 * This is called from ProcessIncomingNotify()
			 */
			Assert(type == READ_ONLY_COMMITTED);

			notifications = NIL;
			reachedStop = asyncQueueGetEntriesByPage(&pos, head,
								   &notifications, CurrentMemoryContext,
								   NULL, CurrentMemoryContext);

			foreach(lc, notifications)
			{
				n = (Notification *) lfirst(lc);
				NotifyMyFrontEnd(n->channel, n->payload, n->srcPid);
			}
		}
	} while (!reachedStop);

	LWLockAcquire(AsyncQueueLock, LW_SHARED);
	QUEUE_BACKEND_POS(MyBackendId) = pos;
	if (QUEUE_POS_EQUAL(oldpos, QUEUE_TAIL))
		advanceTail = true;
	LWLockRelease(AsyncQueueLock);

	if (advanceTail)
		/* Move forward the tail pointer and try to truncate. */
		asyncQueueAdvanceTail();
}

static void
asyncQueueAdvanceTail()
{
	QueuePosition	min;
	int				i;
	int				tailPage;
	int				headPage;

	LWLockAcquire(AsyncQueueLock, LW_EXCLUSIVE);
	min = QUEUE_HEAD;
	for (i = 0; i < MaxBackends; i++)
		if (QUEUE_BACKEND_PID(i) != InvalidPid)
			min = QUEUE_POS_MIN(min, QUEUE_BACKEND_POS(i), QUEUE_HEAD);

	tailPage = QUEUE_POS_PAGE(QUEUE_TAIL);
	headPage = QUEUE_POS_PAGE(QUEUE_HEAD);
	QUEUE_TAIL = min;
	LWLockRelease(AsyncQueueLock);

	/* This is our wraparound check */
	if (asyncQueuePagePrecedesLogically(tailPage, QUEUE_POS_PAGE(min), headPage)
		&& asyncQueuePagePrecedesPhysically(tailPage, headPage))
	{
		/*
		 * SimpleLruTruncate() will ask for AsyncCtlLock but will also
		 * release the lock again.
		 *
		 * Don't even bother grabbing the lock if we can only truncate at most
		 * one page...
		 */
		if (QUEUE_POS_PAGE(min) - tailPage > SLRU_PAGES_PER_SEGMENT)
			SimpleLruTruncate(AsyncCtl, QUEUE_POS_PAGE(min));
	}
}

/*
 * ProcessIncomingNotify
 *
 *		Deal with arriving NOTIFYs from other backends.
 *		This is called either directly from the PROCSIG_NOTIFY_INTERRUPT
 *		signal handler, or the next time control reaches the outer idle loop.
 *		Scan the queue for arriving notifications and report them to my front
 *		end.
 *
 *		NOTE: we are outside of any transaction here.
 */
static void
ProcessIncomingNotify(void)
{
	bool			catchup_enabled;

	Assert(GetCurrentTransactionIdIfAny() == InvalidTransactionId);

	/* Must prevent catchup interrupt while I am running */
	catchup_enabled = DisableCatchupInterrupt();

	if (Trace_notify)
		elog(DEBUG1, "ProcessIncomingNotify");

	set_ps_display("notify interrupt", false);

	notifyInterruptOccurred = 0;

	/*
 	 * Work on the uncommitted notifications list until we hit the first
	 * still-running transaction.
	 */
	while(uncommittedNotifications != NIL)
	{
		ListCell	   *lc;
		Notification   *n;

		n = (Notification *) linitial(uncommittedNotifications);
		if (TransactionIdDidCommit(n->xid))
		{
			if (IsListeningOn(n->channel))
				NotifyMyFrontEnd(n->channel, n->payload, n->srcPid);
		}
		else
		{
			if (!TransactionIdDidAbort(n->xid))
				/* n->xid still running */
				break;
		}
		pfree(n->channel);
		pfree(n->payload);
		lc = list_head(uncommittedNotifications);
		uncommittedNotifications
			= list_delete_cell(uncommittedNotifications, lc, NULL);
	}

	asyncQueueReadAllNotifications(READ_ONLY_COMMITTED);

	/*
	 * Must flush the notify messages to ensure frontend gets them promptly.
	 */
	pq_flush();

	set_ps_display("idle", false);

	if (Trace_notify)
		elog(DEBUG1, "ProcessIncomingNotify: done");

	if (catchup_enabled)
		EnableCatchupInterrupt();
}

/*
 * Send NOTIFY message to my front end.
 */
static void
NotifyMyFrontEnd(const char *channel, const char *payload, int32 srcPid)
{
	if (whereToSendOutput == DestRemote)
	{
		StringInfoData buf;

		pq_beginmessage(&buf, 'A');
		pq_sendint(&buf, srcPid, sizeof(int32));
		pq_sendstring(&buf, channel);
		if (PG_PROTOCOL_MAJOR(FrontendProtocol) >= 3)
			pq_sendstring(&buf, payload);
		pq_endmessage(&buf);

		/*
		 * NOTE: we do not do pq_flush() here.	For a self-notify, it will
		 * happen at the end of the transaction, and for incoming notifies
		 * ProcessIncomingNotify will do it after finding all the notifies.
		 */
	}
	else
		elog(INFO, "NOTIFY for %s", channel);
}

/* Does pendingNotifies include the given channel/payload? */
static bool
AsyncExistsPendingNotify(const char *channel, const char *payload)
{
	ListCell   *p;
	Notification *n;

	if (pendingNotifies == NIL)
		return false;

	if (payload == NULL)
		payload = "";

	/*
	 * We need to append new elements to the end of the list in order to keep
	 * the order. However, on the other hand we'd like to check the list
	 * backwards in order to make duplicate-elimination a tad faster when the
	 * same condition is signaled many times in a row. So as a compromise we
	 * check the tail element first which we can access directly. If this
	 * doesn't match, we check the rest of whole list.
	 */

	n = (Notification *) llast(pendingNotifies);
	if (strcmp(n->channel, channel) == 0)
	{
		Assert(n->payload != NULL);
		if (strcmp(n->payload, payload) == 0)
			return true;
	}

	/*
	 * Note the difference to foreach(). We stop if p is the last element
	 * already. So we don't check the last element, we have checked it already.
 	 */
	for(p = list_head(pendingNotifies);
		p != list_tail(pendingNotifies);
		p = lnext(p))
	{
		n = (Notification *) lfirst(p);

		if (strcmp(n->channel, channel) == 0)
		{
			Assert(n->payload != NULL);
			if (strcmp(n->payload, payload) == 0)
				return true;
		}
	}

	return false;
}

/* Clear the pendingActions and pendingNotifies lists. */
static void
ClearPendingActionsAndNotifies(void)
{
	/*
	 * We used to have to explicitly deallocate the list members and nodes,
	 * because they were malloc'd.  Now, since we know they are palloc'd in
	 * CurTransactionContext, we need not do that --- they'll go away
	 * automatically at transaction exit.  We need only reset the list head
	 * pointers.
	 */
	pendingActions = NIL;
	pendingNotifies = NIL;
}

/*
 * 2PC processing routine for COMMIT PREPARED case.
 *
 * (We don't have to do anything for ROLLBACK PREPARED.)
 */
void
notify_twophase_postcommit(TransactionId xid, uint16 info,
						   void *recdata, uint32 len)
{
	/*
	 * Set up to issue the NOTIFY at the end of my own current transaction.
	 * (XXX this has some issues if my own transaction later rolls back, or if
	 * there is any significant delay before I commit.	OK for now because we
	 * disallow COMMIT PREPARED inside a transaction block.)
	 */
	AsyncQueueEntry		*qe = (AsyncQueueEntry *) recdata;

	Assert(qe->dboid == MyDatabaseId);
	Assert(qe->length == len);

	Async_Notify(qe->channel, qe->payload);
}

