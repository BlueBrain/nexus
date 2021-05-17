package ch.epfl.bluebrain.nexus.delta.sourcing.config

import ch.epfl.bluebrain.nexus.delta.kernel.Secret

/**
  * The cassandra database config.
  *
  * @param contactPoints      the collection of contact points
  * @param keyspace           the keyspace to use for the event log
  * @param snapshotKeyspace   the keyspace to use for snapshots
  * @param username           the cassandra username
  * @param password           the cassandra password
  * @param keyspaceAutocreate when true it creates the keyspace on service boot
  * @param tablesAutocreate   when true it creates the tables on service boot
  */
final case class CassandraConfig(
    contactPoints: Set[String],
    keyspace: String,
    snapshotKeyspace: String,
    username: String,
    password: Secret[String],
    keyspaceAutocreate: Boolean,
    tablesAutocreate: Boolean
)
