public class MagicAnnotationConfiguration extends AnnotationConfiguration
	{
	public MagicAnnotationConfiguration()
		{
		super();
		NotifyListener listener = new NotifyListener();
		this.getSessionEventListenerConfig().setLoadEventListener(listener);
		this.getSessionEventListenerConfig().setFlushEventListener(listener);
		}

	public MagicSessionFactory buildSessionFactory()
		{
		SessionFactory sf = super.buildSessionFactory();
		return (MagicSessionFactory) sf;
		}
	}
