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

package fr.keyconsulting.oliphant.postgresql;

import java.util.Iterator;

import org.hibernate.HibernateException;
import org.hibernate.mapping.*;
import org.hibernate.cfg.Configuration;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.Mapping;

public class PostgreSQLListenerAuxiliary extends AbstractAuxiliaryDatabaseObject
	{
	private static final long serialVersionUID = 1L;
	private Configuration config;

	public PostgreSQLListenerAuxiliary(Configuration config)
		{
		addDialectScope("org.hibernate.dialect.PostgreSQLDialect");
		this.config = config;
		}

	public String sqlCreateString(Dialect dialect, Mapping mapping, String defaultCatalog, String defaultSchema)
		{
		StringBuilder sb = new StringBuilder();

		for(Iterator i = config.getClassMappings(); i.hasNext();)
			{
			PersistentClass c = (PersistentClass) i.next();
			Table table = c.getTable();
			if (table.getPrimaryKey().getColumnSpan()>1)
				{
				throw new HibernateException("Oliphant does not support primary keys that span multiple columns. Objects of class "+c.getClassName()+" will not be monitored for changes.");
				}
			String tableName = table.getName().toLowerCase();

			String idColName = table.getPrimaryKey().getColumn(0).getName();

			Iterator verCols = c.getVersion().getColumnIterator();
			if (!verCols.hasNext())
				{
				throw new HibernateException("Oliphant does not support version properties that span multiple columns. Objects of class "+c.getClassName()+" will not be monitored for changes.");
				}
			Column verCol = (Column) verCols.next();
			String verColName = verCol.getName();
			if (verCols.hasNext())
				{
				throw new HibernateException("Oliphant does not support non versioned entities. Objects of class "+c.getClassName()+" will not be monitored for changes.");
				}
			sb.append("CREATE OR REPLACE FUNCTION oliphant_"+tableName+"() RETURNS TRIGGER AS $$\n");
			sb.append("	DECLARE\n");
			sb.append("		VERSION TEXT;\n");
			sb.append("	BEGIN\n");
			sb.append("		IF TG_OP = 'UPDATE' THEN\n");
			sb.append("			VERSION := NEW."+verColName+";\n");
			sb.append("		ELSIF TG_OP = 'DELETE' THEN\n");
			sb.append("			VERSION := -1;\n");
			sb.append("		END IF;\n");
			sb.append("		PERFORM send_notify('oliphant', '"+tableName+"#' || encode(text(OLD."+idColName+")::bytea,'base64') || '###' || VERSION); RETURN NULL;\n");
			sb.append("	END;\n");
			sb.append("$$ LANGUAGE 'plpgsql';\n");
			sb.append("\n");
			sb.append("CREATE TRIGGER oliphant_"+tableName+"_trg\n");
			sb.append("	AFTER DELETE OR UPDATE ON "+tableName+"\n");
			sb.append("	FOR EACH ROW EXECUTE PROCEDURE oliphant_"+tableName+"();\n");
			sb.append("\n");
			}

		return sb.toString();
		}

	public String sqlDropString(Dialect dialect, String defaultCatalog, String defaultSchema)
		{
		StringBuilder sb = new StringBuilder();

		for(Iterator i = config.getClassMappings(); i.hasNext();)
			{
			PersistentClass c = (PersistentClass) i.next();
			Table table = c.getTable();
			if ((table.getPrimaryKey().getColumnSpan()==1) && (c.getVersion().getColumnSpan()==1))
				{
				String tableName = table.getName().toLowerCase();

				sb.append("DROP FUNCTION oliphant_"+tableName+"()\n");
				sb.append("\n");
				}
			}

		return sb.toString();
		}

	}
