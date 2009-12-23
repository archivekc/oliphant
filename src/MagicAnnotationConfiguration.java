import org.hibernate.cfg.AnnotationConfiguration;
import org.hibernate.event.FlushEntityEventListener;
import org.hibernate.event.PostLoadEventListener;
import org.hibernate.event.def.DefaultFlushEntityEventListener;
import org.hibernate.event.def.DefaultPostLoadEventListener;

public class MagicAnnotationConfiguration extends AnnotationConfiguration
	{
	private static final long serialVersionUID = 1L;

	public MagicAnnotationConfiguration()
		{
		super();
		NotifyListener listener = new NotifyListener();
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
	}
