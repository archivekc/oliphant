/*******************************************************************************

   Copyright (C) 2009-1010 Key Consulting

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
	private final int NB_ROWS = 4002;
	
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

		Statement stmt = conn.createStatement();
		stmt.executeUpdate("DROP FUNCTION PersistentVersionedObject_notification()");
		stmt.executeUpdate("CREATE FUNCTION PersistentVersionedObject_notification() RETURNS TRIGGER AS $$ DECLARE a integer; BEGIN a = my_notify('persistentversionedobject#' || NEW.ID || '###' || NEW.VERSION); RETURN NULL; END; $$ LANGUAGE 'plpgsql';");
		stmt.executeUpdate("CREATE TRIGGER PersistentVersionedObject_update_trigger AFTER DELETE OR UPDATE ON persistentversionedobject FOR EACH ROW EXECUTE PROCEDURE PersistentVersionedObject_notification();");
		stmt.close();
		}
	
	public void staleLoad(long i) throws SQLException
		{
		Session session = magicSessionFactory.getCurrentSession();
		
		System.out.println("Hibernate: starting transaction");
		Transaction tx = session.beginTransaction();
		
		Statement st = conn.createStatement();
		System.out.println("Updating object "+i+" outside Hibernate");
		st.executeUpdate("UPDATE persistentversionedobject SET version=version+1 WHERE id="+i);
		st.close();
		
		System.out.println("Hibernate: loading object "+i);
		PersistentVersionedObject o = (PersistentVersionedObject) session.load(PersistentVersionedObject.class, i);
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
	
	public void update(long id, boolean magic, boolean stale) throws SQLException
		{
		SessionFactory factory = magic ? magicSessionFactory : sessionFactory;
		
		Session session = factory.getCurrentSession();
		
		System.out.println("Hibernate: starting transaction");
		Transaction tx = session.beginTransaction();
		PersistentVersionedObject o1 = (PersistentVersionedObject) session.load(PersistentVersionedObject.class, id+1);
		System.out.println("Hibernate: loading object "+id);
		PersistentVersionedObject o = (PersistentVersionedObject) session.load(PersistentVersionedObject.class, id);
		o.setChampString("valeur 2");	
		
		if (stale)
			{
			Statement st = conn.createStatement();
			System.out.println("Updating object "+id+" outside Hibernate");
			st.executeUpdate("UPDATE persistentversionedobject SET version=version+1 WHERE id="+id);
			st.close();
			}
		
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

		System.out.println("=== Simple update (normal) ===");
		long simpleNormalStartTime = System.currentTimeMillis();
		for(long i=0; i<1000; i++)
			{
			test.update(i, false, false);
			}
		long simpleNormalEndTime = System.currentTimeMillis();
		long simpleNormalTime = simpleNormalEndTime - simpleNormalStartTime;
		
		System.out.println("=== Simple update (magic) ===");
		long simpleMagicStartTime = System.currentTimeMillis();
		for(long i=1000; i<2000; i++)
			{
			test.update(i, true, false);
			}
		long simpleMagicEndTime = System.currentTimeMillis();
		long simpleMagicTime = simpleMagicEndTime - simpleMagicStartTime;
		
		double magicCost = Math.floor(100*simpleMagicTime/simpleNormalTime);
		
		System.out.println("=== Normal update : "+simpleNormalTime+" ms , Normal magic update : "+simpleMagicTime+" ms -> magic = "+magicCost+"% x normal ===");
		
		System.out.println("=== Stale load ===");
		test.staleLoad(4000);

		System.out.println("=== Stale update (normal) ===");
		long staleNormalStartTime = System.currentTimeMillis();
		for(long i=2000; i<3000; i++)
			{
			test.update(i,false, true);
			}
		long staleNormalEndTime = System.currentTimeMillis();
		long staleNormalTime = staleNormalEndTime - staleNormalStartTime;
		
		System.out.println("=== Stale update (magic) ===");
		long staleMagicStartTime = System.currentTimeMillis();
		for(long i=3000; i<4000; i++)
			{
			test.update(i,true, true);
			}
		long staleMagicEndTime = System.currentTimeMillis();
		long staleMagicTime = staleMagicEndTime - staleMagicStartTime;

		double magicGain = Math.floor(100*staleMagicTime/staleNormalTime);

		System.out.println("=== Normal update : "+staleNormalTime+" ms , Magic update : "+staleMagicTime+" ms -> magic = "+magicGain+"% x normal ===");
		

		System.out.println("=== Stale update (magic, cached) ===");
		test.update(3, true, false);
		}
	}
