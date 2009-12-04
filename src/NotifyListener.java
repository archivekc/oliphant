class NotifyFlushEventListener extends EventListener
{
	private staleUids; // Map de session_id -> Map de UID (string identifiant table + objet) -> true ?
	private versions; // Map de UID -> derniere version connue

	public Serializable onSessionCreation ?? (SessionCreationEvent event) throws HibernateException {
		updateStaleUidsAndVersions();
		staleUids.Clear(); // On ne sait pas de quand datent les notifs, donc celles qui ne nous indique pas de numero de version et ont été émises avant le début de cette session ne sont pas intérprétables
		}

	public Serializable onLoad(PersistEvent event) throws HibernateException {
		// il est peut etre deja stale. Peut on le verifier dans le cas ou on est versionne ? -> est on appele juste apres ou juste avant le load ? Dans ce cas, on regarde si dans staleIds on a une version et si c'est le cas on la compare a notre version. Si pas dans dirtyid on est clean. Si version correcte on retire de dirtyIds de cette session. Si version ancienne, on staleobjectstateexception, et on garde le dirtyid.
		// Cas pas de versionnage : pn enregistre que l'objet est clean pour cette session donc on le retire des dirtyIds de cette session
		return processEvent(event);
		}

	public Serializable onPersist(PersistEvent event) throws HibernateException {
		return processEvent(event);
		}

	public Serializable onFlush(FlushEvent event) throws HibernateException {
		return processEvent(event);
		}

	public Serializable onCommit(CommitEvent event) throws HibernateException {
		return processEvent(event);
		}

	public Serializable processEvent(PersistEvent event) throws HibernateException {
		updateStaleUidsAndVersions();
		Object object = event.getObject();
		if (isKnownToBeStale(object), event.getSession()) {
			if (isKnownToBeStaleInL2(object)) { session.getSessionFactory().evict(object); } // l'objet est stale en L2, on le retire du cache L2
			throw new StaleObjectStateException(object.class, id); // pas toujours pour un load, configurable
			}
		return null;
		}

	public boolean isKnownToBeStaleInL2(Object object) {
		Versioning.getVersion(Object[] fields, EntityPersister persister) // Extract the optimisitc locking value out of the entity state snapshot.
		// comparer version L2 et derniere version connue
		// retourner toujours false pour les non versionnes
		}

	public boolean isKnownToBeStale(Object object, Session session) {
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
