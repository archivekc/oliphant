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
		StringBuilder sb = new StringBuilder();

		for(Iterator i = config.getTableMappings(); i.hasNext();)
			{
			Table t = (Table) i.next();
			String table = t.getName().toLowerCase();
			
			sb.append("CREATE OR REPLACE FUNCTION oliphant_"+table+"() RETURNS TRIGGER AS $$\n");
			sb.append("	DECLARE\n");
			sb.append("		VERSION TEXT;\n");
			sb.append("	BEGIN\n");
			sb.append("		IF TG_OP = 'UPDATE' THEN\n");
			sb.append("			VERSION := NEW.VERSION;\n");
			sb.append("		ELSIF TG_OP = 'DELETE' THEN\n");
			sb.append("			VERSION := -1;\n");
			sb.append("		END IF;\n");
			sb.append("		PERFORM send_notify('oliphant', '"+table+"#' || OLD.ID || '###' || VERSION); RETURN NULL;\n");
			sb.append("	END;\n");
			sb.append("$$ LANGUAGE 'plpgsql';\n");
			sb.append("\n");
			sb.append("CREATE TRIGGER oliphant_"+table+"_trg\n");
			sb.append("	AFTER DELETE OR UPDATE ON "+table+"\n");
			sb.append("	FOR EACH ROW EXECUTE PROCEDURE oliphant_"+table+"();\n");
			sb.append("\n");
			}

		return sb.toString();
		}

	public String sqlDropString(Dialect dialect, String defaultCatalog, String defaultSchema)
		{
		StringBuilder sb = new StringBuilder();

		for(Iterator i = config.getTableMappings(); i.hasNext();)
			{
			Table t = (Table) i.next();
			String table = t.getName().toLowerCase();
			
			sb.append("DROP FUNCTION oliphant_"+table+"()\n");
			sb.append("\n");
			}

		return sb.toString();
		}

	}
