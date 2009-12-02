/*-------------------------------------------------------------------------
 *
 * async.h
 *	  Asynchronous notification: NOTIFY, LISTEN, UNLISTEN
 *
 * Portions Copyright (c) 1996-2009, PostgreSQL Global Development Group
 * Portions Copyright (c) 1994, Regents of the University of California
 *
 * $PostgreSQL: pgsql/src/include/commands/async.h,v 1.38 2009/07/31 20:26:23 tgl Exp $
 *
 *-------------------------------------------------------------------------
 */
#ifndef ASYNC_H
#define ASYNC_H

/*
 * How long can a payload string possibly be? Actually it needs to be one
 * byte less to provide space for the trailing terminating '\0'.
 */
#define NOTIFY_PAYLOAD_MAX_LENGTH	8000

/*
 * How many page slots do we reserve ?
 */
#define NUM_ASYNC_BUFFERS			4

extern bool Trace_notify;

extern void AsyncShmemInit(void);

/* notify-related SQL statements */
extern void Async_Notify(const char *relname, const char *payload);
extern void Async_Listen(const char *relname);
extern void Async_Unlisten(const char *relname);
extern void Async_UnlistenAll(void);

/* perform (or cancel) outbound notify processing at transaction commit */
extern void AtCommit_NotifyBeforeCommit(void);
extern void AtCommit_NotifyAfterCommit(void);
extern void AtAbort_Notify(void);
extern void AtSubStart_Notify(void);
extern void AtSubCommit_Notify(void);
extern void AtSubAbort_Notify(void);
extern void AtPrepare_Notify(void);

/* signal handler for inbound notifies (PROCSIG_NOTIFY_INTERRUPT) */
extern void HandleNotifyInterrupt(void);

/*
 * enable/disable processing of inbound notifies directly from signal handler.
 * The enable routine first performs processing of any inbound notifies that
 * have occurred since the last disable.
 */
extern void EnableNotifyInterrupt(void);
extern bool DisableNotifyInterrupt(void);

extern void notify_twophase_postcommit(TransactionId xid, uint16 info,
						   void *recdata, uint32 len);

#endif   /* ASYNC_H */
