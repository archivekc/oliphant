package fr.keyconsulting.oliphant;

import java.util.List;
import org.hibernate.cfg.Configuration;

public interface SpecificNotifyListener
	{
	public void setUp(Configuration config); // Setup the notification system (create triggers, subscribe to update notifications, etc ?)
	public List<Notification> getLatestUpdates(); // Return the latest notifications
	public void tearDown(); // Close the system properly (remove triggers, unsubscribe, etc ?)
	}
