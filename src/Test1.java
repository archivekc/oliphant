import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.junit.Before;
import org.junit.Test;

public class Test1 {
	private Connection conn;
	private SessionFactory sessionFactory;
	private final int NB_ROWS = 2000;
	
	@Before
	public void setUp() throws SQLException {
		conn = Utils.getJDBCConnection();
		sessionFactory = Utils.getSessionFactory();
		Session session = sessionFactory.getCurrentSession();
		Transaction tx = session.beginTransaction();   
		for (int i = 0; i<NB_ROWS; i++) {
			ObjetPersistent o = new ObjetPersistent();
			o.setId(i);
			o.setChampString("valeur string");
			o.setChampLong((long) 1);
			session.save(o);
		    }
		tx.commit();
	}
	
	@Test
	public void simpleUpdate() {
		Session session = sessionFactory.getCurrentSession();
		
		Transaction tx = session.beginTransaction();
		ObjetPersistent o = (ObjetPersistent) session.load(ObjetPersistent.class, (long) 1);
		o.setChampString("valeur 1");
		tx.commit();

		assertTrue(true);
	}
	
	@Test
	public void simpleUpdate2() {
		Session session = sessionFactory.getCurrentSession();
		
		Transaction tx = session.beginTransaction();
		ObjetPersistent o = (ObjetPersistent) session.load(ObjetPersistent.class, (long) 2);
		o.setChampString("valeur 1");
		tx.commit();

		assertTrue(true);
	}
	
	@Test
	public void staleUpdate() throws SQLException {
		Session session = sessionFactory.getCurrentSession();
		
		Transaction tx = session.beginTransaction();
		ObjetPersistent o = (ObjetPersistent) session.load(ObjetPersistent.class, 2);
		o.setChampString("valeur 2");	
		
		Statement st = conn.createStatement();
		ResultSet rs = st.executeQuery("UPDATE objet SET champstring='valeur 3' WHERE id=2");
		rs.close();
		st.close();

		tx.commit();
		
		assertTrue(true);
	}


	@Test
	public void simpleUpdateBenchmark() {
		assertTrue(true);
	}

	
	@Test
	public void staleUpdateBenchmark() {
		assertTrue(true);
	}

}
