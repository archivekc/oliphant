import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

public class TestVersioned
	{
	private Connection conn;
	private SessionFactory magicSessionFactory;
	private SessionFactory sessionFactory;
	private final int NB_ROWS = 2000;
	
	public void setUp() throws SQLException
		{
		conn = Utils.getJDBCConnection();

		sessionFactory = Utils.getSessionFactory();
		magicSessionFactory = Utils.getMagicSessionFactory();

		Session session = sessionFactory.getCurrentSession();
		Transaction tx = session.beginTransaction();   
		for (int i = 0; i<NB_ROWS; i++)
			{
			PersistentVersionedObject o = new PersistentVersionedObject();
			o.setId(i);
			o.setChampString("valeur string");
			o.setChampLong((long) 1);
			session.save(o);
			if (i%20  == 0)
				{
				session.flush();
				session.clear();
				}
			}
		session.flush();
		session.clear();
		tx.commit();
		
		Statement st = conn.createStatement();
		st.executeUpdate("DROP FUNCTION PersistentVersionedObject_notification()");
		st.executeUpdate("CREATE FUNCTION PersistentVersionedObject_notification() RETURNS TRIGGER AS $$ DECLARE a integer; BEGIN a = my_notify('PersistentVersionedObject#' || NEW.ID || '###' || NEW.VERSION); RETURN NULL; END; $$ LANGUAGE 'plpgsql';");
		st.executeUpdate("CREATE TRIGGER PersistentVersionedObject_update_trigger AFTER DELETE OR UPDATE ON persistentversionedobject FOR EACH ROW EXECUTE PROCEDURE PersistentVersionedObject_notification();");
		st.close();
		}
	
	public void simpleUpdate()
		{
		Session session = sessionFactory.getCurrentSession();
		
		Transaction tx = session.beginTransaction();
		PersistentVersionedObject o = (PersistentVersionedObject) session.load(PersistentVersionedObject.class, (long) 1);
		o.setChampString("valeur 1");
		tx.commit();

		System.out.println(sessionFactory.getStatistics());		
		}

	public void staleLoad() throws SQLException
		{
		Session session = sessionFactory.getCurrentSession();
		
		System.out.println("Hibernate: starting transaction");
		Transaction tx = session.beginTransaction();
		
		Statement st = conn.createStatement();
		System.out.println("Updating object 1 outside Hibernate");
		st.executeUpdate("UPDATE persistentversionedobject SET version=version+1 WHERE id=1");
		st.close();
		
		System.out.println("Hibernate: loading object 1");
		PersistentVersionedObject o = (PersistentVersionedObject) session.load(PersistentVersionedObject.class, (long) 1);
		o.setChampString("valeur 1");
		try
			{
			tx.commit();
			}
		catch (Exception e)
			{
			System.out.println(e);
			System.out.println("  in "+e.getStackTrace()[0]);
			}

		System.out.println(sessionFactory.getStatistics());		
		}
	
	public void staleUpdate(long id, boolean magic) throws SQLException
		{
		SessionFactory factory = magic ? magicSessionFactory : sessionFactory;
		
		Session session = factory.getCurrentSession();
		
		System.out.println("Hibernate: starting transaction");
		Transaction tx = session.beginTransaction();
		System.out.println("Hibernate: loading object "+id);
		PersistentVersionedObject o = (PersistentVersionedObject) session.load(PersistentVersionedObject.class, id);
		o.setChampString("valeur 2");	
		
		Statement st = conn.createStatement();
		System.out.println("Updating object "+id+" outside Hibernate");
		st.executeUpdate("UPDATE persistentversionedobject SET version=version+1 WHERE id="+id);
		st.close();
		
		try
			{
			session.persist(o);
			tx.commit();
			}
		catch (Exception e)
			{
			System.out.println(e);
			System.out.println("  in "+e.getStackTrace()[0]);
			}		
		}

	public static void main(String[] args) throws Exception
		{
		TestVersioned test = new TestVersioned();
		test.setUp();
		System.out.println("=== Simple update ===");
		test.simpleUpdate();
		System.out.println("=== Stale load ===");
		test.staleLoad();
		System.out.println("=== Stale update (magic) ===");
		long magicStartTime = System.currentTimeMillis();
		for(long i=0; i<1000; i++)
			{
			test.staleUpdate(i,true);
			}
		long magicEndTime = System.currentTimeMillis();
		long magicTime = magicEndTime - magicStartTime;
		System.out.println("=== Stale update (normal) ===");
		long normalStartTime = System.currentTimeMillis();
		for(long i=1000; i<2000; i++)
			{
			test.staleUpdate(i,false);
			}
		long normalEndTime = System.currentTimeMillis();
		long normalTime = normalEndTime - normalStartTime;
		double percent = Math.floor(100*magicTime/normalTime);
		System.out.println("=== Normal update : "+normalTime+" ms , Magic update : "+magicTime+" ms -> magic = "+percent+"% x normal ===");
		}
	}
