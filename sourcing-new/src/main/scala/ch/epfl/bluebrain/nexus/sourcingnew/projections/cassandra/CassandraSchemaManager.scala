package ch.epfl.bluebrain.nexus.sourcingnew.projections.cassandra

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.effect.{Async, ContextShift, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.SchemaManager

class CassandraSchemaManager[F[_]](session: CassandraSession,
                                   schemaConfig: CassandraConfig,
                                   as: ActorSystem[Nothing])
                                  (implicit F: Async[F]) extends SchemaManager[F] {

  import schemaConfig._
  import projectionConfig._

  val createKeyspace: String =
    s"""CREATE KEYSPACE IF NOT EXISTS $keyspace
       |WITH REPLICATION = { 'class' : $replicationStrategy }""".stripMargin

  val createProgressTable: String =
    s"""CREATE TABLE IF NOT EXISTS $keyspace.$progressTable (
       |projection_id varchar primary key, progress text)""".stripMargin

  val createFailuresTable: String =
    s"""CREATE TABLE IF NOT EXISTS $keyspace.$failuresTable (
       |projection_id varchar, offset text, persistence_id text, sequence_nr bigint, value text,
       |PRIMARY KEY (projection_id, offset, persistence_id, sequence_nr))
       |WITH CLUSTERING ORDER BY (offset ASC)""".stripMargin

  override def migrate(): F[Unit] = {
    import ch.epfl.bluebrain.nexus.sourcingnew.projections.Projection._
    implicit val cs: ContextShift[IO] = IO.contextShift(as.executionContext)

    def keyspace =
      if (keyspaceAutoCreate) wrapFuture(session.executeDDL(createKeyspace)) >> F.unit
      else F.unit

    def progress =
      if (tablesAutoCreate) wrapFuture(session.executeDDL(createProgressTable)) >> F.unit
      else F.unit

    def failures =
      if (tablesAutoCreate) wrapFuture(session.executeDDL(createFailuresTable)) >> F.unit
      else F.unit

    for {
      _ <- keyspace
      _ <- progress
      _ <- failures
    } yield ()
  }
}
