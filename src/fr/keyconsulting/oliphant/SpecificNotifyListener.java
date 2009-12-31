package fr.keyconsulting.oliphant;

import java.util.List;

public interface SpecificNotifyListener
	{
	public void setUp(); // Setup the notification system (create triggers, subscribe to update notifications, etc ?)
	public List<Notification> getLatestUpdates(); // Return the latest notifications
	public void tearDown(); // Close the system properly (remove triggers, unsubscribe, etc ?)
	}
