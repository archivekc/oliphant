package fr.keyconsulting.oliphant;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AnnotationConfiguration;
import org.hibernate.event.FlushEntityEventListener;
import org.hibernate.event.PersistEventListener;
import org.hibernate.event.PostLoadEventListener;
import org.hibernate.event.PreUpdateEventListener;
import org.hibernate.event.def.DefaultFlushEntityEventListener;
import org.hibernate.event.def.DefaultPersistEventListener;
import org.hibernate.event.def.DefaultPostLoadEventListener;

public class MagicAnnotationConfiguration extends AnnotationConfiguration
	{
	private static final long serialVersionUID = 1L;
	private NotifyListener listener;
	
	public MagicAnnotationConfiguration()
		{
		super();
		listener = new NotifyListener();

		PostLoadEventListener[] originalPostLoadListeners = this.getEventListeners().getPostLoadEventListeners();
		int originalPostLoadListenersSize = java.lang.reflect.Array.getLength(originalPostLoadListeners);
		PostLoadEventListener[] postLoadEventListeners = new PostLoadEventListener[originalPostLoadListenersSize+1];
		postLoadEventListeners[0] = listener;
		System.arraycopy(originalPostLoadListeners,0,postLoadEventListeners,1,originalPostLoadListenersSize);
		this.getEventListeners().setPostLoadEventListeners(postLoadEventListeners);
		
		PersistEventListener[] originalPersistEventListeners = this.getEventListeners().getPersistEventListeners();
		int originalPersistEventListenersSize = java.lang.reflect.Array.getLength(originalPersistEventListeners);
		PersistEventListener[] persistEventListeners = new PersistEventListener[originalPersistEventListenersSize+1];
		persistEventListeners[0] = listener;
		System.arraycopy(originalPersistEventListeners,0,persistEventListeners,1,originalPersistEventListenersSize);
		this.getEventListeners().setPersistEventListeners(persistEventListeners);

		FlushEntityEventListener[] originalFlushEntityEventListeners = this.getEventListeners().getFlushEntityEventListeners();
		int originalFlushEntityEventListenersSize = java.lang.reflect.Array.getLength(originalFlushEntityEventListeners);
		FlushEntityEventListener[] flushEntityEventListeners = new FlushEntityEventListener[originalFlushEntityEventListenersSize+1];
		flushEntityEventListeners[0] = listener;
		System.arraycopy(originalFlushEntityEventListeners,0,flushEntityEventListeners,1,originalFlushEntityEventListenersSize);
		this.getEventListeners().setFlushEntityEventListeners(flushEntityEventListeners);
		
		PreUpdateEventListener[] originalPreUpdateEventListeners = this.getEventListeners().getPreUpdateEventListeners();
		int originalPreUpdateEventListenersSize = java.lang.reflect.Array.getLength(originalPreUpdateEventListeners);
		PreUpdateEventListener[] preUpdateEventListeners = new PreUpdateEventListener[originalPreUpdateEventListenersSize+1];
		preUpdateEventListeners[0] = listener;
		System.arraycopy(originalPreUpdateEventListeners,0,preUpdateEventListeners,1,originalPreUpdateEventListenersSize);
		this.getEventListeners().setPreUpdateEventListeners(preUpdateEventListeners);
		}
	
	public SessionFactory buildSessionFactory()
		{
		String driver = this.getProperty("hibernate.connection.driver_class");
		if (driver.equals("org.postgresql.Driver"))
			{
			listener.setSpecificListener(new PostgreSQLNotifyListener());
			}
		return super.buildSessionFactory();
		}
	}
