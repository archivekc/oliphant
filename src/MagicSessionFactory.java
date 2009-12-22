import org.hibernate.SessionFactory;

public class MagicSessionFactory extends SessionFactory
	{
	private List<Session> sessions;

	public MagicSessionFactory(SessionFactory s)
		{
		}

	public List<Session> getSessions()
		{
		return sessions;
		}

	public Session openSession()
		{
		Session session = super.openSession();
		sessions.Add(session);
		return session;
		}

	public Session openSession(Connection connection)
		{
		super.openSession(connection);
		}

	public Session openSession(Connection connection, boolean flushBeforeCompletionEnabled, boolean autoCloseSessionEnabled, ConnectionReleaseMode connectionReleaseMode)
		{
		super.openSession(connection, flushBeforeCompletionEnabled, autoCloseSessionEnabled, connectionReleaseMode);
		}

	public Session openSession(Connection connection, Interceptor sessionLocalInterceptor)
		{
		super.openSession(connection, sessionLocalInterceptor);
		}

	public Session openSession(Interceptor sessionLocalInterceptor)
		{
		super.openSession(sessionLocalInterceptor);
		}
	}
