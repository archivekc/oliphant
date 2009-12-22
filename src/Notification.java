public class Notification
	{
	private Long version;
	private String uid;

	public Notification(String[] infos)
		{
		uid = infos[0];
		version = new Long(infos[1]);
		}
	
	public Long getVersion()
		{
		return version;
		}

	public void setVersion(long v)
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
