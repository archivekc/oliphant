class NotifyFlushEventListener extends EventListener
{
	private staleIds; // Map de session_id -> Map de UID (string identifiant table + objet) -> true ?

	public Serializable onflush(FlushEvent event) throws HibernateException {
		return onPersist(event);
		}

	public Serializable onPersist(PersistEvent event) throws HibernateException {
		Object object = event.getObject();
		if (isStale(object), event.getSession()) {throw new StaleObjectStateException(object.class, id); }
		return null;
		}

	public Serializable onLoad(PersistEvent event) throws HibernateException {
		// On enregistre que l'objet est clean pour cette session donc on le retire des dirtyIds de cette session
		}

	public boolean isStale(Object object, Session session) {
		updateStaleUids();
		return ...
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

	private void updateStaleUids(Session session) {
		udpateStaleTablesAndIds(session, session.connection());
		}

	// PostgreSQL
	private function udpateStaleUids(Session session, PGConnection conn) {
		org.postgresql.PGNotification notifications[] = pgconn.getNotifications();
		if (notifications != null) {
			for (int i=0; i<notifications.length; i++) {
				Class c = notifications[i].getPayload()... // todo : retrouver la classe avec le mapping... on ne peut pas mettre le nom de la classe directement dans le notify, les applis pouvant avoir differents noms de classe pour la meme table
					pareil pour id
		}

	// Oracle
	private function udpateStaleTablesAndIds(Session session, OracleConnection conn) {
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
