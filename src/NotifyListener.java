
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.HibernateException;
import org.hibernate.SessionFactory;
import org.hibernate.Session;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.StaleObjectStateException;
import org.hibernate.event.*;

public class NotifyListener implements LoadEventListener, PostLoadEventListener, RefreshEventListener, PersistEventListener, DirtyCheckEventListener, FlushEventListener, PreUpdateEventListener
	{
	private HashMap staleUids = new HashMap(); // Map de session -> Map de UID (string identifiant table + objet) -> true ?
					       // Selon comment se fait la comparaison des objets session, il pourra etre necessaire
					       // de faire un objet qui prend une session dans le constructeur et definit un nouvel equal
					       // qui verifie juste si on a affaire a la meme instance de session
	private HashMap versions = new HashMap(); // Map de UID -> derniere version connue
	private SessionFactory sessionFactory;
	private SpecificNotifyListener specificNotifyListener;

	public Serializable ProcessLoadEvent(AbstractEvent event, boolean throwStaleException) throws StaleObjectStateException
		{
		if (sessionFactory == null) {sessionFactory = event.getSession().getSessionFactory();} // our first event, initialize the sessionFactory
		// il est peut etre deja stale. on regarde si dans versions on a cet objet et si c'est le cas on compare a notre version, sinon on considère comme clean (pas moyen de savoir). Si version correcte on retire de staleIds de cette session. Si version ancienne, on staleobjectstateexception, et on garde le staleId. Si pas de version connue, on garde la version comme plus recente connue.
		// Cas pas de versionnage : on retire l'objet des dirtyIds de cette session. On a pas de moyen de savoir si il est stale.
		return true;
		}

	public void onPostLoad(PostLoadEvent event) throws StaleObjectStateException
		{
		ProcessLoadEvent(event, true);
		processEvent(event);
		}
	
	public void onLoad(LoadEvent event, LoadType type) throws StaleObjectStateException
		{
		ProcessLoadEvent(event, true);
		processEvent(event);
		}
	
	public void onRefresh(RefreshEvent event, Map map) throws StaleObjectStateException
		{
		ProcessLoadEvent(event, true);
		processEvent(event);
		}

	public void onRefresh(RefreshEvent event) throws StaleObjectStateException
		{
		ProcessLoadEvent(event, true);
		processEvent(event);
		}

	public void onPersist(PersistEvent event, Map map) throws StaleObjectStateException
		{
		processEvent(event);
		}

	public void onPersist(PersistEvent event) throws StaleObjectStateException
		{
		processEvent(event);
		}
	
	public void onDirtyCheck(DirtyCheckEvent event) throws StaleObjectStateException
		{
		processEvent(event);
		}

	public void onFlush(FlushEvent event) throws StaleObjectStateException
		{
		processEvent(event);
		}

	public boolean onPreUpdate(PreUpdateEvent event) throws StaleObjectStateException
		{
		processEvent(event);
		return true;
		}

	public Serializable processEvent(AbstractEvent event) throws StaleObjectStateException
		{
		PersistentClass object = (PersistentClass) ((RefreshEvent) event).getObject();
		Session session = event.getSession();
		updateStaleUidsAndVersions();
		if (isKnownToBeStaleInSession(object, session))
			{
			if (isKnownToBeStaleInL2(object))
				{
				sessionFactory.evict(session.getEntityName(object), session.getIdentifier(object));
				}
			throw new StaleObjectStateException(object.class, object.getIdentifierProperty().get); // TODO: Should be optional for loads
			}
		return null;
		}

	public boolean isKnownToBeStaleInL2(PersistentClass object)
		{
		return false;
		// TODO : check cache, maybe do it in a separate cache manager ?
		/*
	 	EntityPersister persister = sessionFactory.getEntityPersister(entityName);
		if (persister.isVersionned())
			{
			object.getVersion()
			sessionFactory.
			// comparer version L2 et derniere version connue. Comment recuperer version L2 ?
			}
		else
			{
			// We can't know what version is in L2, so we can't know if it's stale
			return false;
			}
		*/
		}

	public boolean isKnownToBeStaleInSession(PersistentClass object, Session session)
		{
		String objectUid = uid(object);
		updateStaleUidsAndVersions();
		//if ((staleIds.ContainsKey(session)) && (staleIds.get(session).ContainsKey(objectUid))) {return true;}
		if (versions.containsKey(objectUid))
			{
			if (!object.getVersion().equals(versions.get(objectUid))) {return true;}
			}
		return false;
		}

	private String uid(PersistentClass object) // Unique Identifier for the object, used in database notifications
		{
		return object.getClass()+"#"+object.getKey();
		}

	private void updateStaleUidsAndVersions()
		{
		List<Notification> updates = specificNotifyListener.getLatestUpdates();
		for (int i=0; i<updates.size(); i++)
			{
			Notification notif = updates.get(i);
			if (notif.getVersion() != null) {versions.put(notif.getUid(), notif.getVersion());}
			List<Session> sessions = sessionFactory.getSessions();
			for (int j=0; j<sessions.size(); j++)
				{
				Session session = sessions.get(j);
				if (!staleUids.containsKey(session)) {staleUids.put(session, new HashMap());}
				((HashMap) staleUids.get(session)).put(notif.getUid(), true);
				}
			}
		}
 
	private void garbageCollector()
		{
		// TODO: remove the keys of closed sessions from the staleUids Map
		}
	}
