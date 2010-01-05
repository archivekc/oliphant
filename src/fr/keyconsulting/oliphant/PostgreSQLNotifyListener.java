/*******************************************************************************

   Copyright (C) 2009-1010 Key Consulting

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

package fr.keyconsulting.oliphant;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.cfg.Configuration;

class PostgreSQLNotifyListener implements SpecificNotifyListener
	{
	private BufferedReader br;
	
	public void setUp(Configuration config)
		{       
		String filename = config.getProperty("oliphant.file_notify.file");
		if (filename == null)
			{
			filename = "/var/lib/postgres/my_notify";
			}
		try {
			RandomAccessFile rand = new RandomAccessFile(filename,"rw");
			rand.setLength(0);
			rand.close();
			}
		catch (Exception e)
			{
			e.printStackTrace();
			}
		File file = new File(filename);
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
