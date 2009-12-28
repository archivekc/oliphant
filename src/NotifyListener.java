
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.cache.CacheKey;
import org.hibernate.cache.access.EntityRegionAccessStrategy;
import org.hibernate.cache.entry.CacheEntry;
import org.hibernate.engine.SessionFactoryImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.StaleObjectStateException;
import org.hibernate.event.*;

public class NotifyListener implements PostLoadEventListener, PersistEventListener, FlushEntityEventListener, PreUpdateEventListener
	{
	private static final long serialVersionUID = 1L;
	private HashMap<String,String> versions = new HashMap<String,String>(); // Maps object UIDs to latest known versions
	private SessionFactoryImplementor sessionFactory;
	private SpecificNotifyListener specificNotifyListener;

	public void onPostLoad(PostLoadEvent event) throws StaleObjectStateException
		{
		System.out.println("Hibernate: Post load event");
		ProcessLoadEvent(event, true);
		checkObject(event.getEntity(), event.getSession());
		}
	
	public void onPersist(PersistEvent event, Map map) throws StaleObjectStateException
		{
		System.out.println("Hibernate:  Persist event");
		checkObject(event.getObject(), event.getSession());
		}

	public void onPersist(PersistEvent event) throws StaleObjectStateException
		{
		System.out.println("Hibernate:  Persist event");
		checkObject(event.getObject(), event.getSession());
		}
	
	public void onFlushEntity(FlushEntityEvent event) throws StaleObjectStateException
		{
		System.out.println("Hibernate:  Flush entity event");
		checkObject(event.getEntity(), event.getSession());
		}

	public boolean onPreUpdate(PreUpdateEvent event)
		{
		System.out.println("Hibernate:  Pre-update event");
		checkObject(event.getEntity(), event.getSession());
		return true;
		}
	
	public Serializable ProcessLoadEvent(PostLoadEvent event, boolean throwStaleException) throws StaleObjectStateException
	{
	if (sessionFactory == null)
		{
		// our first event, initialize the listener
		sessionFactory = (SessionFactoryImplementor) event.getSession().getSessionFactory();
		}
	Object object = event.getEntity();
	EventSource session = event.getSession();
	EntityPersister persister = event.getPersister();
	String uid = getUid(object, session);
	if (persister.isVersioned())
		{
		if (!versions.containsKey(uid))
			{
			// We have not yet received notifications for this object
			versions.put(uid, persister.getVersion(object, session.getEntityMode()).toString());
			}
		}
	return true;
	}
	
	public Serializable checkObject(Object object, EventSource session) throws StaleObjectStateException
		{
		updateStaleUidsAndVersions();
		Serializable identifier = session.getIdentifier(object);
		System.out.print("* Checking object "+identifier+" : ");
		if (isKnownToBeStaleInSession(object, session))
			{
			System.out.print("Object is stale in session");
			String entityName = session.getEntityName(object);
			if (isKnownToBeStaleInL2(object, session))
				{
				System.out.println(" and in L2 cache");
				evictFromL2(object, session);
				}
			else
				{
				System.out.println();
				}
			throw new StaleObjectStateException(entityName, identifier); // TODO: Should be optional for loads
			}
		System.out.println("Object is not verifiably stale");
		return null;
		}

	public boolean isKnownToBeStaleInL2(Object object, EventSource session)
		{
		final EntityPersister persister = sessionFactory.getEntityPersister(session.getEntityName(object));
		String uid = getUid(object, session);
		if (!versions.containsKey(uid)) {return false;}
		if (persister.isVersioned())
			{
			if (persister.hasCache() && session.getCacheMode().isGetEnabled())
				{
				final EntityRegionAccessStrategy cacheAccessStrategy = persister.getCacheAccessStrategy();
				if (cacheAccessStrategy==null) {return false;}
				final CacheKey ck = new CacheKey(
						session.getIdentifier(object),
						persister.getIdentifierType(),
						persister.getRootEntityName(),
						session.getEntityMode(),
						session.getFactory()
						);
				CacheEntry cachedObject = (CacheEntry) cacheAccessStrategy.get(ck, Long.MAX_VALUE);
				if (cachedObject==null) {return false;}
				if (cachedObject.getDisassembledState()[persister.getVersionProperty()] != versions.get(uid)) {return true;}
				}
			}
		return false;
		}
	
	public void evictFromL2(Object object, EventSource session)
	{
	final EntityPersister persister = sessionFactory.getEntityPersister(session.getEntityName(object));
	if (persister.isVersioned())
		{
		if (persister.hasCache() && session.getCacheMode().isGetEnabled())
			{
			final EntityRegionAccessStrategy cacheAccessStrategy = persister.getCacheAccessStrategy();
			if (cacheAccessStrategy==null) {return;}
			final CacheKey ck = new CacheKey(
					session.getIdentifier(object),
					persister.getIdentifierType(),
					persister.getRootEntityName(),
					session.getEntityMode(),
					session.getFactory()
					);
			cacheAccessStrategy.evict(ck);
			Serializable identifier = session.getIdentifier(object);
			System.out.println("* Object "+identifier+" evicted from L2");
			}
		}
	}

	public boolean isKnownToBeStaleInSession(Object object, EventSource session)
		{
		String uid = getUid(object, session);
		updateStaleUidsAndVersions();
		if (versions.containsKey(uid))
			{
			EntityPersister persister = session.getEntityPersister(session.getEntityName(object), object);
			String version = persister.getVersion(object, session.getEntityMode()).toString();
			if (!version.equals(versions.get(uid))) {return true;}
			}
		return false;
		}

	private String getUid(Object object, EventSource session)
		{
		String entityName = session.getEntityName(object);
		String id = session.getIdentifier(object).toString();
		return entityName+"#"+id;
		}

	private void updateStaleUidsAndVersions()
		{
		List<Notification> updates = specificNotifyListener.getLatestUpdates();
		for (int i=0; i<updates.size(); i++)
			{
			Notification notif = updates.get(i);
			if (notif.getVersion() != null) {versions.put(notif.getUid(), notif.getVersion());}
			}
		}
 
	public void setSpecificListener(SpecificNotifyListener specList)
		{
		specificNotifyListener = specList;
		specificNotifyListener.setUp();
		}
	}
