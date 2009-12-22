CREATE OR REPLACE FUNCTION my_c_notify(cstring) RETURNS int AS '/srv/http/workspace/hibernate_cir/postgresql/mynotify/mynotify.so', 'notify' LANGUAGE 'C' STRICT;

CREATE OR REPLACE FUNCTION my_notify(s TEXT) RETURNS int AS
	$$
	BEGIN
		RETURN my_c_notify(textout(s)::cstring);
	END
	$$ LANGUAGE 'plpgsql';

CREATE TRIGGER PersistentVersionnedObject_update_trigger
        AFTER DELETE OR UPDATE ON objet_persistent
        FOR EACH ROW EXECUTE PROCEDURE notification()

CREATE OR REPLACE FUNCTION PersistentVersionnedObject_notification() RETURNS OPAQUE AS
	$$
	BEGIN
		SELECT my_notify('PersistentVersionnedObject###' || NEW.ID || '###' || NEW.VERSION);
	END;
	$$ LANGUAGE 'plpgsql';

