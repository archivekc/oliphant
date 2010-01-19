/*******************************************************************************

   Copyright (C) 2009-2010 Key Consulting

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

package fr.keyconsulting.oliphant.test;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StaleObjectStateException;
import org.hibernate.Transaction;
import org.hibernate.cfg.AnnotationConfiguration;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Table;

import static org.junit.Assert.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class unitTests
	{
	private Connection conn;
	private SessionFactory magicSessionFactory;
	private SessionFactory sessionFactory;
	private AnnotationConfiguration config;
	
	@Before
	public void setUp() throws SQLException
		{
		conn = Utils.getJDBCConnection();
		config = Utils.getConfig();

		Statement st = conn.createStatement();
		for(Iterator i = config.getClassMappings(); i.hasNext();)
			{
			PersistentClass c = (PersistentClass) i.next();
			st.executeUpdate("DELETE FROM "+c.getTable().getName());
			}
		st.close();

		sessionFactory = Utils.getSessionFactory();
		magicSessionFactory = Utils.getMagicSessionFactory();

		Session session = sessionFactory.getCurrentSession();
		Transaction tx = session.beginTransaction();   
		
		PersistentVersionedObject o = new PersistentVersionedObject();
		o.setChampString("valeur string");
		o.setChampLong((long) 1);
		session.save(o);
		session.flush();
		session.clear();
		tx.commit();
		}
	
	@After
	public void tearDown()
		{
		sessionFactory.getCurrentSession().close();
		magicSessionFactory.getCurrentSession().close();
		}
	
	public void update(Class theClass, boolean magic, boolean stale, boolean cached)
		{
		long id = 0;
		SessionFactory factory = magic ? magicSessionFactory : sessionFactory;
		
		Session session = factory.getCurrentSession();
		
		System.out.println("Hibernate: starting transaction");
		Transaction tx = session.beginTransaction();
		
		if (cached)
			{
			System.out.println("Hibernate: pre-loading object "+id);
			 Object o = theClass.cast(session.load(theClass, id));
			}
		else
			{
			factory.evict(theClass);
			}
		System.out.println("Hibernate: loading "+theClass+" "+id);
		PersistentVersionedObject o = (PersistentVersionedObject) session.load(theClass, id);
		o.setChampString(""+System.currentTimeMillis());
		
		if (stale)
			{
			try
				{
				System.out.println("Updating "+theClass+" "+id+" outside Hibernate");
				Statement st = conn.createStatement();
				String entityName = session.getEntityName(o);
				PersistentClass c = config.getClassMapping(entityName);
				st.executeUpdate("UPDATE "+c.getTable().getName()+" SET version=version+1 WHERE id="+id);
				st.close();
				}
			catch (SQLException e)
				{
				fail();
				System.out.println(e);
				System.out.println("  in "+e.getStackTrace()[0]);
				}
			}
		try
			{
			session.persist(o);
			}
		catch (StaleObjectStateException e)
			{
			assertTrue(magic && stale);
			return;
			}
		assertFalse(stale && magic); // We should have had an exception
		try
			{
			tx.commit();
			}
		catch (StaleObjectStateException e)
			{
			assertTrue(stale);
			return;
			}
		assertFalse(stale); // We should have had an exception
		}

	@Test
	public void simpleNormalUpdate()
		{
		update(PersistentVersionedObject.class, false, false, false);
		}
		
	@Test
	public void simpleMagicUpdate()
		{
		update(PersistentVersionedObject.class, true, false, false);
		}

	@Test
	public void staleNormalUpdate()
		{
		update(PersistentVersionedObject.class, false, true, false);
		}

	@Test
	public void staleMagicUpdate()
		{
		update(PersistentVersionedObject.class, true, true, false);
		}
	}
