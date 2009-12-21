CREATE OR REPLACE FUNCTION my_c_notify(cstring) RETURNS int AS '/srv/http/workspace/hibernate_cir/postgresql/mynotify/mynotify.so', 'notify' LANGUAGE 'C' STRICT;

CREATE OR REPLACE FUNCTION my_notify(s TEXT) RETURNS int AS
	$$
	BEGIN
		RETURN my_c_notify(textout(s)::cstring);
	END
	$$ LANGUAGE 'plpgsql';

CREATE TRIGGER notify_objet_persistent
        AFTER DELETE OR UPDATE ON objet_persistent
        FOR EACH ROW EXECUTE PROCEDURE notification()

CREATE OR REPLACE FUNCTION notification() RETURNS OPAQUE AS
	$$
	BEGIN
		SELECT my_notify('OBJET_PERSISTENT###' || NEW.ID);
	END;
	$$ LANGUAGE 'plpgsql';

