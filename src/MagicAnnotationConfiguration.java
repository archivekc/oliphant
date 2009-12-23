import org.hibernate.SessionFactory;
import org.hibernate.cfg.AnnotationConfiguration;
import org.hibernate.event.FlushEntityEventListener;
import org.hibernate.event.PostLoadEventListener;
import org.hibernate.event.def.DefaultFlushEntityEventListener;
import org.hibernate.event.def.DefaultPostLoadEventListener;

public class MagicAnnotationConfiguration extends AnnotationConfiguration
	{
	private static final long serialVersionUID = 1L;
	private NotifyListener listener;
	
	public MagicAnnotationConfiguration()
		{
		super();
		listener = new NotifyListener();
		PostLoadEventListener[] postLoadListeners = new PostLoadEventListener[2];
		postLoadListeners[0] = listener;
		postLoadListeners[1] = new DefaultPostLoadEventListener();
		this.setListeners("post-load", postLoadListeners);
		/*this.setListener("persist", listener);*/
		FlushEntityEventListener[] flushEntityListeners = new FlushEntityEventListener[2];
		flushEntityListeners[0] = listener;
		flushEntityListeners[1] = new DefaultFlushEntityEventListener();
		this.setListeners("flush-entity", flushEntityListeners);
		}
	
	public SessionFactory buildSessionFactory()
		{
		String driver = this.getProperty("hibernate.connection.driver_class");
		if (driver.equals("org.postgresql.Driver"))
			{
			listener.setSpecificListener(new PostgreSQLNotifyListener());
			}
		else if (driver.equals("oracle.jdbc.driver.OracleDriver"))
			{
			listener.setSpecificListener(new OracleNotifyListener());
			}
		return super.buildSessionFactory();
		}
	}
