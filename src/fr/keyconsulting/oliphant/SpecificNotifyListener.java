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

import java.util.List;
import org.hibernate.cfg.Configuration;

public interface SpecificNotifyListener
	{
	public void prepare(Configuration config); // Add triggers to the DDL, and keep the configuration for future reference
	public void setUp(); // Connect to DB, subscribe to update notifications
	public List<Notification> getLatestUpdates(); // Return the latest notifications
	public void tearDown(); // Close the system properly (remove triggers, unsubscribe, etc ?)
	}
