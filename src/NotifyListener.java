class NotifyFlushEventListener extends EventListener
{
	private staleIds; // Map de session_id -> Map de UID (string identifiant table + objet) -> derniere version ou null ?

	public Serializable onflush(FlushEvent event) throws HibernateException {
		return onPersist(event);
		}

	public Serializable onPersist(PersistEvent event) throws HibernateException {
		Object object = event.getObject();
		if (isStale(object), event.getSession()) {throw new StaleObjectStateException(object.class, id); }
		return null;
		}

	public Serializable onLoad(PersistEvent event) throws HibernateException {
		updateStaleUids();
		// il est peut etre deja stale. Peut on le verifier dans le cas ou on est versionne ? -> est on appele juste apres ou juste avant le load ? Dans ce cas, on regarde si dans staleIds on a une version et si c'est le cas on la compare a notre version. Si pas dans dirtyid on est clean. Si version correcte on retire de dirtyIds de cette session. Si version ancienne, on staleobjectstateexception, et on garde le dirtyid.
		// Cas pas de versionnage : pn enregistre que l'objet est clean pour cette session donc on le retire des dirtyIds de cette session
		}

	public boolean isStale(Object object, Session session) {
		if (in staleIds uid(object)) {return true;}
		updateStaleUids();
		if (in staleIds uid(object)) {return true;}
		}

	/*public ... getStaleObjectsClassesAndIds(Session session) {
		TODO (Peut etre ?)
		if (session.get(c, id) !== null) {throw new StaleObjectStateException(c, id);
		}

	private getClassName(String tableName)
		{
		for (Iterator iter = cfg.getClassMappings(); iter.hasNext(); ) {
		   PersistentClass pc = (PersistentClass) iter.next();
		   if (tableName.equalsIgnoreCase(pc.getTable().getName())) {
		      className = pc.getClassName();
		   }
		}*/

	private string uid(Object object) {
		}

	private void updateStaleUids(Session session) {
		string[] newStaleUids = getLatestUpdates(session, session.connection());
		for (int i-p; i<newStaleUids.
		// on ajoute tout aux dirtyIds pour cette session
		}

	// PostgreSQL
	private string[] getLatestUpdates(Session session, PGConnection conn) {
		org.postgresql.PGNotification notifications[] = pgconn.getNotifications();
		string[] latestUpdates;
		if (notifications != null) {
			for (int i=0; i<notifications.length; i++) {latestUpdates.add(notifications[i].getPayload());}
			}
		}

	// Oracle
	private string[] getLatestUpdates(Session session, OracleConnection conn) {
		...
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
