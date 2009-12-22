class PostgreSQLNotifyListener implements SpecificNotifyListener
	{
	public void setUp()
		{
		}

	private Map getLatestUpdates(PGConnection conn)
		{
		List<Notifications> notifs;

		File file = new File("/var/lib/postgre/data/my_notify");
		FileInputStream fis = new FileInputStream(file);
		BufferedInputStream bis = new BufferedInputStream(fis);
		DataInputStream dis = new DataInputStream(bis);

		while (dis.available() != 0)
			{
			String line = dis.readLine();
			System.out.println(line);
			notifs.Add(new Notification(line.split("###")));
			}

		fis.close();
		bis.close();
		dis.close();

		return notifs;
		}
	/*
	private Map getLatestUpdates(PGConnection conn)
		{
		org.postgresql.PGNotification notifications[] = pgconn.getNotifications();
		string[] latestUpdates;
		if (notifications != null)
			{
			for (int i=0; i<notifications.length; i++) {latestUpdates.add(notifications[i].getPayload());}
			}
		}
	*/

	public void tearDown()
		{
		}
	}
