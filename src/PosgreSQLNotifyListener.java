import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

class PostgreSQLNotifyListener implements SpecificNotifyListener
	{
	private BufferedReader br;
	
	public void setUp()
		{
		File file = new File("/var/lib/postgres/my_notify");
		FileInputStream fis;
		try
			{
			fis = new FileInputStream(file);
			br = new BufferedReader(new InputStreamReader(fis));
			}
		catch (FileNotFoundException e)
			{
			e.printStackTrace();
			}
		}

	public List<Notification> getLatestUpdates()
		{
		List<Notification> notifs = new ArrayList<Notification>();

		try
			{
			while (br.ready())
				{
				String line = br.readLine();
				System.out.println("Notif from PostgreSQL : "+line);
				notifs.add(new Notification(line.split("###")));
				}
			}
		catch (IOException e)
			{
			e.printStackTrace();
			}

		return notifs;
		}
	
	public void tearDown()
		{
		try
			{
			br.close();
			}
		catch (IOException e)
			{
			e.printStackTrace();
			}
		}
	}
