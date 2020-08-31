package ch.epfl.bluebrain.nexus.sourcing.projections.cassandra

trait CassandraStatements {

  def createKeyspace(keyspace: String, replicationStrategy: String): String =
    s"""CREATE KEYSPACE IF NOT EXISTS $keyspace
       |WITH REPLICATION = { 'class' : $replicationStrategy }""".stripMargin

  def createProgressTable(keyspace: String): String =
    s"""CREATE TABLE IF NOT EXISTS $keyspace.projections_progress (
       |projection_id varchar primary key, offset text, processed bigint, discarded bigint, failed bigint)""".stripMargin

  def createFailuresTable(keyspace: String): String =
    s"""CREATE TABLE IF NOT EXISTS $keyspace.projections_failures (
       |projection_id varchar, offset text, persistence_id text, sequence_nr bigint, value text, error_type varchar, error text,
       |PRIMARY KEY (projection_id, offset, persistence_id, sequence_nr))
       |WITH CLUSTERING ORDER BY (offset ASC)""".stripMargin

}

object CassandraStatements extends CassandraStatements