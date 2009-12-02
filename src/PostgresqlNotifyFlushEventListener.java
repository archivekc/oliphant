class PostgresqlNotifyFlushEventListener extends DefaultFlushEventListener
{
	public Serializable onFlush(FlushEvent event)
		throws HibernateException {
			if( event.getObject() instanceof User ) {
				Session session = (Session)event.getSession();

				pgconn = session.connection();

				org.postgresql.PGNotification notifications[] = pgconn.getNotifications();
				if (notifications != null) {
					for (int i=0; i<notifications.length; i++) {
						Class c = notifications[i].getPayload()...
							pareil pour id
							if (session.get(c, id) !== null) {throw new StaleObjectStateException(...);}
					}
				}

				return null;
			}
		}
}

// Pour utiliser ce listener, dans la conf :
//<hibernate-configuration>
//    <session-factory>
//        ...
//        <event type="flush">
//            <listener class="ce listener"/>
//            <listener class="org.hibernate.event.def.DefaultLoadEventListener"/>
//        </event>
//    </session-factory>
//</hibernate-configuration>
