/*******************************************************************************

   Copyright (C) 2009-2010 Key Consulting

   This file is part of Oliphant.
 
   Oliphant is free software: you can redistribute it and/or modify
   it under the terms of the GNU Lesser General Public License as published
   by the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.
 
   Oliphant is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU Lesser General Public License for more details.
 
   You should have received a copy of the GNU Lesser General Public
   License along with Oliphant.  If not, see <http://www.gnu.org/licenses/>.

*******************************************************************************/

package fr.keyconsulting.oliphant;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.HibernateException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.cache.CacheKey;
import org.hibernate.cache.access.EntityRegionAccessStrategy;
import org.hibernate.cache.entry.CacheEntry;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.SessionFactoryImplementor;
import org.hibernate.event.EventSource;
import org.hibernate.event.FlushEntityEvent;
import org.hibernate.event.FlushEntityEventListener;
import org.hibernate.event.PersistEvent;
import org.hibernate.event.PersistEventListener;
import org.hibernate.event.PostLoadEvent;
import org.hibernate.event.PostLoadEventListener;
import org.hibernate.event.PreUpdateEvent;
import org.hibernate.event.PreUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NotifyListener implements PostLoadEventListener, PersistEventListener, FlushEntityEventListener, PreUpdateEventListener
	{
	private static final long serialVersionUID = -8582214998956097719L;
	private Map<String,String> versions = new HashMap<String,String>(); // Maps object UIDs to latest known versions
	private SessionFactoryImplementor sessionFactory;
	private SpecificNotifyListener specificNotifyListener;
	private boolean allowStaleLoad = true;
	private Configuration config;
	
	private static final Logger LOG = LoggerFactory.getLogger(NotifyListener.class);
	
	public void onPostLoad(PostLoadEvent event) throws StaleObjectStateException
		{
		LOG.debug("Hibernate: Post load event");
		processLoadEvent(event, true);
		if (!allowStaleLoad)
			{
			updateStaleUidsAndVersions();
			checkObject(event.getEntity(), event.getSession());
			}
		}
	
	public void onPersist(PersistEvent event, Map map) throws StaleObjectStateException
		{
		LOG.debug("Hibernate:  Persist event");
		updateStaleUidsAndVersions();
		checkObject(event.getObject(), event.getSession());
		}

	public void onPersist(PersistEvent event) throws StaleObjectStateException
		{
		LOG.debug("Hibernate:  Persist event");
		updateStaleUidsAndVersions();
		checkObject(event.getObject(), event.getSession());
		}
	
	public void onFlushEntity(FlushEntityEvent event) throws StaleObjectStateException
		{
		LOG.debug("Hibernate:  Flush entity event");
		updateStaleUidsAndVersions();
		checkObject(event.getEntity(), event.getSession());
		}

	public boolean onPreUpdate(PreUpdateEvent event)
		{
		LOG.debug("Hibernate:  Pre-update event");
		updateStaleUidsAndVersions();
		checkObject(event.getEntity(), event.getSession());
		return true;
		}
	
	public Serializable processLoadEvent(PostLoadEvent event, boolean throwStaleException) throws StaleObjectStateException
	{
	if (sessionFactory == null)
		{
		// our first event, initialize the listener
		sessionFactory = (SessionFactoryImplementor) event.getSession().getSessionFactory();
		specificNotifyListener.setUp();
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
		Serializable identifier = session.getIdentifier(object);
		LOG.debug("* Checking object "+identifier+" : ");
		if (isKnownToBeStaleInSession(object, session))
			{
			LOG.debug("Object is stale in session");
			String entityName = session.getEntityName(object);
			if (isKnownToBeStaleInL2(object, session))
				{
				LOG.debug(" and in L2 cache");
				evictFromL2(object, session);
				}
			throw new StaleObjectStateException(entityName, identifier);
			}
		LOG.debug("Object is not verifiably stale");
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
			LOG.debug("* Object "+identifier+" evicted from L2");
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
		String tableName = config.getClassMapping(entityName).getTable().getName().toLowerCase();
		String id = session.getIdentifier(object).toString();
		return tableName+"#"+id;
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
	
	public static void attachListener(Configuration config)
		{
		NotifyListener listener = new NotifyListener();

		listener.config = config;

		PostLoadEventListener[] originalPostLoadListeners = config.getEventListeners().getPostLoadEventListeners();
		int originalPostLoadListenersSize = java.lang.reflect.Array.getLength(originalPostLoadListeners);
		PostLoadEventListener[] postLoadEventListeners = new PostLoadEventListener[originalPostLoadListenersSize+1];
		postLoadEventListeners[0] = listener;
		System.arraycopy(originalPostLoadListeners,0,postLoadEventListeners,1,originalPostLoadListenersSize);
		config.getEventListeners().setPostLoadEventListeners(postLoadEventListeners);
		
		PersistEventListener[] originalPersistEventListeners = config.getEventListeners().getPersistEventListeners();
		int originalPersistEventListenersSize = java.lang.reflect.Array.getLength(originalPersistEventListeners);
		PersistEventListener[] persistEventListeners = new PersistEventListener[originalPersistEventListenersSize+1];
		persistEventListeners[0] = listener;
		System.arraycopy(originalPersistEventListeners,0,persistEventListeners,1,originalPersistEventListenersSize);
		config.getEventListeners().setPersistEventListeners(persistEventListeners);

		FlushEntityEventListener[] originalFlushEntityEventListeners = config.getEventListeners().getFlushEntityEventListeners();
		int originalFlushEntityEventListenersSize = java.lang.reflect.Array.getLength(originalFlushEntityEventListeners);
		FlushEntityEventListener[] flushEntityEventListeners = new FlushEntityEventListener[originalFlushEntityEventListenersSize+1];
		flushEntityEventListeners[0] = listener;
		System.arraycopy(originalFlushEntityEventListeners,0,flushEntityEventListeners,1,originalFlushEntityEventListenersSize);
		config.getEventListeners().setFlushEntityEventListeners(flushEntityEventListeners);
		
		PreUpdateEventListener[] originalPreUpdateEventListeners = config.getEventListeners().getPreUpdateEventListeners();
		int originalPreUpdateEventListenersSize = java.lang.reflect.Array.getLength(originalPreUpdateEventListeners);
		PreUpdateEventListener[] preUpdateEventListeners = new PreUpdateEventListener[originalPreUpdateEventListenersSize+1];
		preUpdateEventListeners[0] = listener;
		System.arraycopy(originalPreUpdateEventListeners,0,preUpdateEventListeners,1,originalPreUpdateEventListenersSize);
		config.getEventListeners().setPreUpdateEventListeners(preUpdateEventListeners);
		try
			{
			Class specListClass = Class.forName(config.getProperty("oliphant.specific_listener"));
			listener.specificNotifyListener = (SpecificNotifyListener) specListClass.newInstance();
			listener.specificNotifyListener.prepare(config);
			}
		catch (ClassNotFoundException e)
			{
			throw new HibernateException(e);
			}
		catch (InstantiationException e)
			{
			throw new HibernateException(e);
			}
		catch (IllegalAccessException e)
			{
			throw new HibernateException(e);
			}
		String allowStaleString = config.getProperty("oliphant.allow_stale_load");
		if ((allowStaleString!=null) && (allowStaleString.equals("false"))) {listener.allowStaleLoad = false;}
		}
	}
