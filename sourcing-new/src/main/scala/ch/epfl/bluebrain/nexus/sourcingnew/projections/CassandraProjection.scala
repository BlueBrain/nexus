package ch.epfl.bluebrain.nexus.sourcingnew.projections

import akka.actor.ActorSystem
import akka.persistence.query.Offset
import akka.stream.Materializer
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.effect.{Async, ContextShift, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.Projection._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.instances._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.ProjectionProgress.NoProgress
import com.typesafe.config.Config
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import fs2.Stream
import streamz.converter._

private class Statements(journalCfg: Config) {
  val keyspace: String            = journalCfg.getString("keyspace")
  val progressTable: String       = journalCfg.getString("projection-progress-table")
  val failuresTable: String       = journalCfg.getString("projection-failures-table")

  val recordProgressQuery: String =
    s"UPDATE $keyspace.$progressTable SET progress = ? WHERE projection_id = ?"

  val progressQuery: String =
    s"SELECT progress FROM $keyspace.$progressTable WHERE projection_id = ?"

  val recordFailureQuery: String =
    s"""INSERT INTO $keyspace.$failuresTable (projection_id, offset, persistence_id, sequence_nr, value)
       |VALUES (?, ?, ?, ?, ?) IF NOT EXISTS""".stripMargin

  val failuresQuery: String =
    s"SELECT offset, value from $keyspace.$failuresTable WHERE projection_id = ? ALLOW FILTERING"
}

private [projections] class CassandraProjections
        [F[_]: ContextShift, A: Encoder: Decoder](session: CassandraSession,
                                    stmts: Statements)(implicit as: ActorSystem, F: Async[F])
  extends Projections[F, A] {
  implicit val cs: ContextShift[IO] = IO.contextShift(as.dispatcher)
  implicit val materializer: Materializer = Materializer.createMaterializer(as)

  override def recordProgress(id: String, progress: ProjectionProgress): F[Unit] =
    wrapFuture(session.executeWrite(stmts.recordProgressQuery, progress.asJson.noSpaces, id)) >> F.unit

  override def progress(id: String): F[ProjectionProgress] =
    wrapFuture(session.selectOne(stmts.progressQuery, id)).flatMap {
      case Some(row) => F.fromTry(decode[ProjectionProgress](row.getString("progress")).toTry)
      case None      => F.pure(NoProgress)
    }

  override def recordFailure(id: String, persistenceId: String, sequenceNr: Long, offset: Offset, value: A): F[Unit] =
    wrapFuture(
      session.executeWrite(
        stmts.recordFailureQuery,
        id,
          offset.asJson.noSpaces,
        persistenceId,
        sequenceNr: java.lang.Long,
        value.asJson.noSpaces
      )
    ) >> F.unit

  override def failures(id: String): Stream[F, (A, Offset)] =
    session
      .select(stmts.failuresQuery, id)
      .map { row => (decode[A](row.getString("value")), decode[Offset](row.getString("offset"))).tupled }
      .collect { case Right(value) => value }.toStream[F]( _ => ())
}
