import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class OracleNotifyListener implements /*DatabaseChangeListener, */SpecificNotifyListener
	{
	public void setUp()
		{
		/*Properties prop = new Properties();
		prop.setProperty(OracleConnection.DCN_NOTIFY_ROWIDS, "true");
		prop.setProperty(OracleConnection.DCN_IGNORE_INSERTOP, "true");
		prop.setProperty(OracleConnection.DCN_NOTIFY_CHANGELAG, 0);*/

		/* DCN_IGNORE_DELETEOP
			If set to true, DELETE operations will not generate any database change event.
		DCN_IGNORE_INSERTOP
			If set to true, INSERT operations will not generate any database change event.
		DCN_IGNORE_UPDATEOP
			If set to true, UPDATE operations will not generate any database change event.
		DCN_NOTIFY_CHANGELAG
			Specifies the number of transactions by which the client is willing to lag behind.
			Note: If this option is set to any value other than 0, then ROWID level granularity of information will not be available in the events, even if the DCN_NOTIFY_ROWIDS option is set to true.
		DCN_NOTIFY_ROWIDS
			Database change events will include row-level details, such as operation type and ROWID.
		DCN_QUERY_CHANGE_NOTIFICATION
			Activates query change notification instead of object change notification.
			Note: This option is available only when running against an 11.0 database.
		NTF_LOCAL_HOST
			Specifies the IP address of the computer that will receive the notifications from the server.
		NTF_LOCAL_TCP_PORT
			Specifies the TCP port that the driver should use for the listener socket.
		NTF_QOS_PURGE_ON_NTFN
			Specifies if the registration should be expunged on the first notification event.
		NTF_QOS_RELIABLE
			Specifies whether or not to make the notifications persistent, which comes at a performance cost.
		NTF_TIMEOUT
			Specifies the time in seconds after which the registration will be automatically expunged by the database. */

		/*DatabaseChangeRegistration dcr = conn.registerDatabaseChangeNotification(prop);
		DCNDemoListener listener = new DCNDemoListener();
		dcr.addListener(listener);*/
		}

	/*public void onDatabaseChangeNotification(DatabaseChangeEvent e)
		{
		System.out.println(e.toString());
		}*/
	
	public List<Notification> getLatestUpdates()
		{
		return new ArrayList<Notification>();
		}

	public void tearDown()
		{
		}
	}
