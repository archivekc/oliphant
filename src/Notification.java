public class Notification
	{
	private String version;
	private String uid;

	public Notification(String[] infos)
		{
		uid = infos[0];
		version = infos[1];
		}
	
	public String getVersion()
		{
		return version;
		}

	public void setVersion(String v)
		{
		version = v;
		}

	public String getUid()
		{
		return uid;
		}

	public void setUid(String u)
		{
		uid = u;
		}
	}
