package ch.epfl.bluebrain.nexus.sourcing.projections.cassandra

import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import ch.epfl.bluebrain.nexus.sourcing.projections.SchemaMigration
import ch.epfl.bluebrain.nexus.sourcing.projections.cassandra.Cassandra.CassandraConfig
import monix.bio.Task

/**
  * Apply migration scripts on Cassandra
  */
private[projections] class CassandraSchemaMigration(session: CassandraSession, config: CassandraConfig) extends SchemaMigration {
  import config._

  override def migrate(): Task[Unit] = {

    def executeDDL(predicate: Boolean, statement: String): Task[Unit] =
      if (predicate)
        Task.deferFuture(session.executeDDL(statement)) >> Task.unit
      else Task.unit

    for {
      _ <- executeDDL(keyspaceAutoCreate, CassandraStatements.createKeyspace(keyspace, replicationStrategy))
      _ <- executeDDL(tablesAutoCreate, CassandraStatements.createProgressTable(keyspace))
      _ <- executeDDL(tablesAutoCreate, CassandraStatements.createFailuresTable(keyspace))
    } yield ()
  }
}
