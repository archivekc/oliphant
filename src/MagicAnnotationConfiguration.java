import org.hibernate.cfg.AnnotationConfiguration;

public class MagicAnnotationConfiguration extends AnnotationConfiguration
	{
	private static final long serialVersionUID = 1L;

	public MagicAnnotationConfiguration()
		{
		super();
		NotifyListener listener = new NotifyListener();
		this.setListener("LoadEventListener", listener);
		}
	}