
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.EntityMode;
import org.hibernate.HibernateException;
import org.hibernate.engine.SessionFactoryImplementor;
import org.hibernate.Session;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.metadata.ClassMetadata;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.StaleObjectStateException;
import org.hibernate.event.*;

public class NotifyListener implements LoadEventListener, PostLoadEventListener, PersistEventListener, FlushEntityEventListener
	{
	private static final long serialVersionUID = 1L;

	/*private HashMap staleUids = new HashMap(); // Map de session -> Map de UID (string identifiant table + objet) -> true ?
					       // Selon comment se fait la comparaison des objets session, il pourra etre necessaire
					       // de faire un objet qui prend une session dans le constructeur et definit un nouvel equal
					       // qui verifie juste si on a affaire a la meme instance de session*/
	private HashMap<String,String> versions = new HashMap<String,String>(); // Maps object UIDs to latest known versions
	private SessionFactoryImplementor sessionFactory;
	private SpecificNotifyListener specificNotifyListener;

	public Serializable ProcessLoadEvent(PostLoadEvent event, boolean throwStaleException) throws StaleObjectStateException
		{
		if (sessionFactory == null)
			{
			// our first event, initialize the listener
			sessionFactory = (SessionFactoryImplementor) event.getSession().getSessionFactory();
			}
		Object object = event.getEntity();
		EntityPersister persister = event.getPersister();
		String uid = getUid(object);
		if (persister.isVersioned())
			{
			if (!versions.containsKey(uid))
				{
				// We have not yet received notifications for this object
				versions.put(uid, persister.getVersion(object, event.getSession().getEntityMode()).toString());
				}
			}
		else
			{
			// no versionning, we have to remove the uid from dirtyUids for this session (no way of knowing if stale)
			}
		return true;
		}

	public void onPostLoad(PostLoadEvent event) throws StaleObjectStateException
		{
		ProcessLoadEvent(event, true);
		checkObject(event.getEntity(), event.getSession(), event.getPersister().getEntityName());
		}
	
	public void onLoad(LoadEvent event, LoadType type) throws StaleObjectStateException
		{
		}
	
	public void onPersist(PersistEvent event, Map map) throws StaleObjectStateException
		{
		checkObject(event.getObject(), event.getSession(), event.getEntityName());
		}

	public void onPersist(PersistEvent event) throws StaleObjectStateException
		{
		checkObject(event.getObject(), event.getSession(), event.getEntityName());
		}
	
	public void onFlushEntity(FlushEntityEvent event) throws StaleObjectStateException
		{
		checkObject(event.getEntity(), event.getSession(), event.getEntityEntry().getEntityName());
		}

	public Serializable checkObject(Object object, EventSource session, String entityName) throws StaleObjectStateException
		{
		updateStaleUidsAndVersions();
		System.out.println("* Checking object");
		if (isKnownToBeStaleInSession(object, session))
			{
			System.out.println("* Object is stale in session");
			if (isKnownToBeStaleInL2(object))
				{
				sessionFactory.evict(object.getClass(), session.getIdentifier(object));
				}
			throw new StaleObjectStateException(entityName, session.getIdentifier(object)); // TODO: Should be optional for loads
			}
		System.out.println("* Object is not verifiably stale");
		return null;
		}

	public boolean isKnownToBeStaleInL2(Object object)
		{
		return false;
		// TODO : check cache, maybe do it in a separate cache manager ?
		/*
	 	EntityPersister persister = sessionFactory.getEntityPersister(entityName);
		if (persister.isVersioned())
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

	public boolean isKnownToBeStaleInSession(Object object, EventSource session)
		{
		String uid = getUid(object);
		updateStaleUidsAndVersions();
		//if ((staleIds.containsKey(session)) && (staleIds.get(session).ContainsKey(uid))) {return true;}
		if (versions.containsKey(uid))
			{
			EntityPersister persister = session.getEntityPersister(session.getEntityName(object), object);
			String version = persister.getVersion(object, session.getEntityMode()).toString();
			if (!version.equals(versions.get(uid))) {return true;}
			}
		return false;
		}

	private String getUid(Object object) // Unique Identifier for the object, used in database notifications
		{
		//return object.getClass()+"#"+metadata.getPropertyValue(object, metadata.getIdentifierPropertyName(), EntityMode.POJO);
		return object.getClass().getName()+"#"+((PersistentVersionedObject) object).getId();
		}

	private void updateStaleUidsAndVersions()
		{
		List<Notification> updates = specificNotifyListener.getLatestUpdates();
		for (int i=0; i<updates.size(); i++)
			{
			Notification notif = updates.get(i);
			if (notif.getVersion() != null) {versions.put(notif.getUid(), notif.getVersion());}
			/*List<Session> sessions = stae;
			for (int j=0; j<sessions.size(); j++)
				{
				Session session = sessions.get(j);
				if (!staleUids.containsKey(session)) {staleUids.put(session, new HashMap());}
				((HashMap) staleUids.get(session)).put(notif.getUid(), true);
				}*/
			}
		}
 
	private void garbageCollector()
		{
		// TODO: remove the keys of closed sessions from the staleUids Map
		}

	public void setSpecificListener(SpecificNotifyListener specList)
		{
		specificNotifyListener = specList;
		specificNotifyListener.setUp();
		}
	}
