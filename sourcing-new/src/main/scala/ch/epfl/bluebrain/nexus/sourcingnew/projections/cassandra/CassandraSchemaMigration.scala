package ch.epfl.bluebrain.nexus.sourcingnew.projections.cassandra

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.effect.{Async, ContextShift, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.SchemaMigration

object CassandraStatements {

  def createKeyspace(keyspace: String, replicationStrategy: String): String =
    s"""CREATE KEYSPACE IF NOT EXISTS $keyspace
       |WITH REPLICATION = { 'class' : $replicationStrategy }""".stripMargin

  def createProgressTable(keyspace: String, progressTable: String): String =
    s"""CREATE TABLE IF NOT EXISTS $keyspace.$progressTable (
       |projection_id varchar primary key, progress text)""".stripMargin

  def createFailuresTable(keyspace: String, progressTable: String): String =
    s"""CREATE TABLE IF NOT EXISTS $keyspace.$progressTable (
       |projection_id varchar, offset text, persistence_id text, sequence_nr bigint, value text,
       |PRIMARY KEY (projection_id, offset, persistence_id, sequence_nr))
       |WITH CLUSTERING ORDER BY (offset ASC)""".stripMargin
}

class CassandraSchemaMigration[F[_]](session: CassandraSession,
                                     schemaConfig: CassandraConfig,
                                     as: ActorSystem[Nothing])
                                    (implicit F: Async[F]) extends SchemaMigration[F] {
  import schemaConfig._
  import projectionConfig._

  override def migrate(): F[Unit] = {
    import ch.epfl.bluebrain.nexus.sourcingnew.projections.Projection._
    implicit val cs: ContextShift[IO] = IO.contextShift(as.executionContext)

    def executeDDL(predicate: Boolean, statement: String): F[Unit] =
      if(predicate)
        wrapFuture(session.executeDDL(statement)) >> F.unit
      else F.unit

    for {
      _ <- executeDDL(keyspaceAutoCreate, CassandraStatements.createKeyspace(keyspace, replicationStrategy))
      _ <- executeDDL(tablesAutoCreate, CassandraStatements.createProgressTable(keyspace, progressTable))
      _ <- executeDDL(tablesAutoCreate, CassandraStatements.createFailuresTable(keyspace, failuresTable))
    } yield ()
  }
}
