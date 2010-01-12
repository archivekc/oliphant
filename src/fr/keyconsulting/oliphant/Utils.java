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
import java.sql.DriverManager;
import java.sql.SQLException;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.AnnotationConfiguration;

public class Utils
	{
	public static Connection getJDBCConnection() throws SQLException
		{
		String url = "jdbc:postgresql://localhost/hibernate?user=hibernate&password=hibernate333";
		return DriverManager.getConnection(url);
		}
	
	private static final SessionFactory sessionFactory;
	private static final SessionFactory magicSessionFactory;
	static
		{
		try
			{
			AnnotationConfiguration config = new AnnotationConfiguration();
			fillConfig(config);
			sessionFactory = config.buildSessionFactory();
			AnnotationConfiguration magicConfig = new AnnotationConfiguration();
			fillConfig(magicConfig);
			NotifyListener.attachListener(magicConfig);
			magicSessionFactory = magicConfig.buildSessionFactory();
			}
		catch (Throwable ex)
			{
			System.err.println("Initial SessionFactory creation failed." + ex);
			throw new ExceptionInInitializerError(ex);
			}
		}

	public static SessionFactory getSessionFactory()
		{
		return sessionFactory;
		}

	public static SessionFactory getMagicSessionFactory()
		{
		return magicSessionFactory;
		}
	
	private static void fillConfig(AnnotationConfiguration config)
		{
		config.setProperty("hibernate.dialect",
		"org.hibernate.dialect.PostgreSQLDialect");
		config.setProperty("oliphant.specific_listener",
		"fr.keyconsulting.oliphant.FileNotifyListener");
		config.setProperty("oliphant.allow_stale_load",	"true");
		config.setProperty("hibernate.connection.driver_class",
				"org.postgresql.Driver");
		config.setProperty("hibernate.generate_statistics", "true");
		config.setProperty("hibernate.connection.url",
				"jdbc:postgresql://localhost/hibernate");
		config.setProperty("hibernate.connection.username", "hibernate");
		config.setProperty("hibernate.connection.password", "hibernate333");
		config.setProperty("hibernate.connection.pool_size", "1");
		//config.setProperty("hibernate.connection.autocommit", "true");
		config.setProperty("hibernate.cache.provider_class", "org.hibernate.cache.NoCacheProvider");
		config.setProperty("hibernate.hbm2ddl.auto", "create-drop");
		config.setProperty("hibernate.show_sql", "true");
		config.setProperty("hibernate.transaction.factory_class", "org.hibernate.transaction.JDBCTransactionFactory");
		config.setProperty("hibernate.current_session_context_class", "thread");
		
		config.setProperty("hibernate.select_before_update", "true");
		
		config.setProperty("hibernate.cache.use_second_level_cache", "true");
		config.setProperty("hibernate.cache.provider_class", "org.hibernate.cache.EhCacheProvider");

		config.addAnnotatedClass(PersistentVersionedObject.class);
		}
	}
