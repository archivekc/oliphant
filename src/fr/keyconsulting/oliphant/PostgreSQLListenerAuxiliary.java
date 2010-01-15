package fr.keyconsulting.oliphant;

import java.util.Iterator;
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
		String sql = "";
		for(Iterator i = config.getTableMappings(); i.hasNext();)
			{
			Table t = (Table) i.next();
			String table = t.getName().toLowerCase();
			
			sql += "CREATE OR REPLACE FUNCTION oliphant_"+table+"() RETURNS TRIGGER AS $$\n";
			sql += "	DECLARE\n";
			sql += "		VERSION TEXT;\n";
			sql += "	BEGIN\n";
			sql += "		IF TG_OP = 'UPDATE' THEN\n";
			sql += "			VERSION := NEW.VERSION;\n";
			sql += "		ELSIF TG_OP = 'DELETE' THEN\n";
			sql += "			VERSION := -1;\n";
			sql += "		END IF;\n";
			sql += "		PERFORM send_notify('oliphant', '"+table+"#' || OLD.ID || '###' || VERSION); RETURN NULL;\n"; 
			sql += "	END;\n";
			sql += "$$ LANGUAGE 'plpgsql';\n";
			sql += "\n";
			sql += "CREATE TRIGGER oliphant_"+table+"_trg\n";
			sql += "	AFTER DELETE OR UPDATE ON "+table+"\n";
			sql += "	FOR EACH ROW EXECUTE PROCEDURE oliphant_"+table+"();\n";
			sql += "\n";
			}
		return sql;
		}

	public String sqlDropString(Dialect dialect, String defaultCatalog, String defaultSchema)
		{
		String sql = "";
		for(Iterator i = config.getTableMappings(); i.hasNext();)
			{
			Table t = (Table) i.next();
			String table = t.getName().toLowerCase();
			
			sql += "DROP FUNCTION oliphant_"+table+"()\n";
			sql += "\n";
			}
		return sql;
		}

	}
