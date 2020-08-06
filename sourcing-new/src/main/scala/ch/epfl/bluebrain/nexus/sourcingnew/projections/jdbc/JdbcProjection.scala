package ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc

import akka.persistence.query.Offset
import cats.effect.{Async, ContextShift}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcingnew.projections.instances._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.{FailureMessage, Projection, ProjectionId, ProjectionProgress}
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

final case class JdbcConfig(host: String,
                            port: Int,
                            database: String,
                            username: String,
                            password: String,
                            driver: String = "org.postgresql.Driver") {
  def url: String = s"jdbc:postgresql://$host:$port/$database?stringtype=unspecified"
}

class JdbcProjection[F[_]: ContextShift: Async,
                     A: Encoder: Decoder](xa: Transactor[F])
  extends Projection[F, A] {

  /**
    * Records progress against a projection identifier.
    *
    * @param id       the projection identifier
    * @param progress the offset to record
    * @return a future () value
    */
  override def recordProgress(id: ProjectionId, progress: ProjectionProgress): F[Unit] =
    sql"""INSERT into projections_progress(projection_id, akka_offset, processed, discarded, failed)
         |VALUES(${id.value}, ${progress.offset.asJson.noSpaces}, ${progress.processed}, ${progress.discarded}, ${progress.failed})
         |ON CONFLICT (projection_id) DO UPDATE SET akka_offset=EXCLUDED.akka_offset, processed=EXCLUDED.processed, discarded=EXCLUDED.discarded, failed=EXCLUDED.failed""".stripMargin.
      update.run.transact(xa).map(_ => ())

  /**
    * Retrieves the progress for the specified projection projectionId. If there is no record of progress
    * the [[ProjectionProgress.NoProgress]] is returned.
    *
    * @param id an unique projectionId for a projection
    * @return a future progress value for the specified projection projectionId
    */
  override def progress(id: ProjectionId): F[ProjectionProgress] =
    sql"SELECT akka_offset, processed, discarded, failed FROM projections_progress WHERE projection_id = ${id.value}"
      .query[(String, Long, Long, Long)].option.transact(xa).map {
        _.fold(NoProgress) { ProjectionProgress.fromTuple }
      }

  /**
    * Record a specific event against a index failures log projectionId.
    *
    * @param id             the project identifier
    * @param failureMessage the failure message to persist
    */
  override def recordFailure(id: ProjectionId, failureMessage: FailureMessage[A], f: Throwable => String = Projection.stackTraceAsString): F[Unit] =
    sql"""INSERT INTO projections_failures (projection_id, akka_offset, persistence_id, sequence_nr, value, error)
         |VALUES (${id.value}, ${failureMessage.offset.asJson.noSpaces}, ${failureMessage.persistenceId}, ${failureMessage.sequenceNr}, ${failureMessage.value.asJson.noSpaces}, ${f(failureMessage.throwable)})
         |ON CONFLICT DO NOTHING""".stripMargin.update.run.transact(xa).map(_ => ())

  /**
    * An event stream for all failures recorded for a projection.
    *
    * @param id the projection identifier
    * @return a source of the failed events
    */
  override def failures(id: ProjectionId): fs2.Stream[F, (A, Offset, String)] =
    sql"""SELECT value, akka_offset, error from projections_failures WHERE projection_id = ${id.value} ORDER BY ordering"""
      .query[(String, String, String)].stream
      .transact(xa).mapFilter( Projection.decodeError[A, Offset])
}
