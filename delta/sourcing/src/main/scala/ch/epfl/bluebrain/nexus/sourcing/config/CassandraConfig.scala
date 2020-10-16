package ch.epfl.bluebrain.nexus.sourcing.config

/**
  * The cassandra database config.
  * @param contactPoints    the collection of contact points
  * @param keyspace         the keyspace to use for the event log
  * @param snapshotKeyspace the keyspace to use for snapshots
  * @param username         the cassandra username
  * @param password         the cassandra password
  */
final case class CassandraConfig(
    contactPoints: Set[String],
    keyspace: String,
    snapshotKeyspace: String,
    username: String,
    password: String
)
