package ch.epfl.bluebrain.nexus.sourcingnew.projections.cassandra

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import akka.stream.Materializer
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.effect.{Async, ContextShift, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.Projection._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcingnew.projections.instances._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.{FailureMessage, Projection, ProjectionId, ProjectionProgress}
import fs2.Stream
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import streamz.converter._

/**
  * Implementation of [[Projection]] for Cassandra
  *
  * @param session
  * @param projectionConfig
  * @param as
  * @tparam F
  * @tparam A
  */
class CassandraProjection [F[_]: ContextShift,
                           A: Encoder: Decoder](session: CassandraSession,
                                                projectionConfig: ProjectionConfig,
                                                as: ActorSystem[Nothing])(implicit F: Async[F])
  extends Projection[F, A] {
  implicit val cs: ContextShift[IO] = IO.contextShift(as.executionContext)
  implicit val materializer: Materializer = Materializer.createMaterializer(as)

  import projectionConfig._

  val recordProgressQuery: String =
    s"UPDATE $keyspace.$progressTable SET offset = ?, processed = ?, discarded = ?, failed = ? WHERE projection_id = ?"

  val progressQuery: String =
    s"SELECT offset, processed, discarded, failed FROM $keyspace.$progressTable WHERE projection_id = ?"

  val recordFailureQuery: String =
    s"""INSERT INTO $keyspace.$failuresTable (projection_id, offset, persistence_id, sequence_nr, value, error_type, error)
       |VALUES (?, ?, ?, ?, ?, ?, ?) IF NOT EXISTS""".stripMargin

  val failuresQuery: String =
    s"SELECT offset, value, error_type from $keyspace.$failuresTable WHERE projection_id = ? ALLOW FILTERING"

  override def recordProgress(id: ProjectionId, progress: ProjectionProgress): F[Unit] =
    wrapFuture(session.executeWrite(recordProgressQuery,
      progress.offset.asJson.noSpaces,
      progress.processed : java.lang.Long,
      progress.discarded : java.lang.Long,
      progress.failed : java.lang.Long,
      id.value
    )) >> F.unit

  override def progress(id: ProjectionId): F[ProjectionProgress] =
    wrapFuture(session.selectOne(progressQuery, id.value)).map {
      _.fold(NoProgress) { row =>
        ProjectionProgress.fromTuple(
          (
            row.getString("offset"),
            row.getLong("processed"),
            row.getLong("discarded"),
            row.getLong("failed")
          )
        )
      }
    }

  override def recordFailure(id: ProjectionId, failureMessage: FailureMessage[A], f: Throwable => String = Projection.stackTraceAsString): F[Unit] =
    wrapFuture(
      session.executeWrite(
        recordFailureQuery,
        id.value,
        failureMessage.offset.asJson.noSpaces,
        failureMessage.persistenceId,
        failureMessage.sequenceNr: java.lang.Long,
        failureMessage.value.asJson.noSpaces,
        failureMessage.throwable.getClass.getSimpleName,
        f(failureMessage.throwable)
      )
    ) >> F.unit

  override def failures(id: ProjectionId): Stream[F, (A, Offset, String)] =
    session
      .select(failuresQuery, id.value).toStream[F]( _ => ())
      .mapFilter { row =>
        Projection.decodeError[A, Offset](
          (row.getString("value"), row.getString("offset"), row.getString("error_type"))
        )
      }
}
