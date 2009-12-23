import org.hibernate.cfg.AnnotationConfiguration;
import org.hibernate.event.def.DefaultFlushEntityEventListener;
import org.hibernate.event.def.DefaultPostLoadEventListener;

public class MagicAnnotationConfiguration extends AnnotationConfiguration
	{
	private static final long serialVersionUID = 1L;

	public MagicAnnotationConfiguration()
		{
		super();
		NotifyListener listener = new NotifyListener();
		Object[] postLoadListeners = new Object[2];
		postLoadListeners[0] = listener;
		postLoadListeners[1] = new DefaultPostLoadEventListener();
		this.setListeners("post-load", postLoadListeners);
		/*this.setListener("persist", listener);*/
		Object[] flushEntityListeners = new Object[2];
		flushEntityListeners[0] = listener;
		flushEntityListeners[1] = new DefaultFlushEntityEventListener();
		this.setListener("flush-entity", flushEntityListeners);
		}
	}
