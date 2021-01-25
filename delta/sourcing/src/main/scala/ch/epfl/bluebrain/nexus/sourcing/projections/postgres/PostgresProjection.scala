package ch.epfl.bluebrain.nexus.sourcing.projections.postgres

import akka.persistence.query.{NoOffset, Offset, Sequence}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.postgres.PostgresProjection._
import ch.epfl.bluebrain.nexus.sourcing.projections._
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import monix.bio.{Task, UIO}

import java.time.Instant

/**
  * Postgres implementation of [[Projection]]
  */
private[projections] class PostgresProjection[A: Encoder: Decoder](xa: Transactor[Task])(implicit clock: Clock[UIO])
    extends Projection[A] {

  private def offsetToSequence(offset: Offset): Option[Long] =
    offset match {
      case Sequence(value) => Some(value)
      case _               => None
    }

  /**
    * Records progress against a projection identifier.
    *
    * @param id       the projection identifier
    * @param progress the offset to record
    * @return a future () value
    */
  override def recordProgress(id: ProjectionId, progress: ProjectionProgress): Task[Unit] =
    instant.flatMap { timestamp =>
      sql"""INSERT into projections_progress(projection_id, akka_offset, timestamp, processed, discarded, failed)
           |VALUES(${id.value}, ${offsetToSequence(progress.offset)}, ${timestamp.toEpochMilli}, ${progress.processed},
           |${progress.discarded}, ${progress.failed})
           |ON CONFLICT (projection_id) DO UPDATE SET akka_offset=EXCLUDED.akka_offset, timestamp=EXCLUDED.timestamp,
           |processed=EXCLUDED.processed, discarded=EXCLUDED.discarded, failed=EXCLUDED.failed""".stripMargin.update.run
        .transact(xa)
        .map(_ => ())
    }

  /**
    * Retrieves the progress for the specified projection projectionId. If there is no record of progress
    * the [[ProjectionProgress.NoProgress]] is returned.
    *
    * @param id an unique projectionId for a projection
    * @return a future progress value for the specified projection projectionId
    */
  override def progress(id: ProjectionId): Task[ProjectionProgress] =
    sql"SELECT akka_offset, timestamp, processed, discarded, failed FROM projections_progress WHERE projection_id = ${id.value}"
      .query[(Option[Long], Long, Long, Long, Long)]
      .option
      .transact(xa)
      .map {
        _.fold(NoProgress) { case (offset, timestamp, processed, discarded, failed) =>
          ProjectionProgress(
            offset.fold[Offset](NoOffset)(Sequence),
            Instant.ofEpochMilli(timestamp),
            processed,
            discarded,
            failed
          )
        }
      }

  /**
    * Record a specific event against a index failures log projectionId.
    *
    * @param id             the project identifier
    * @param errorMessage   the failure message to persist
    */
  override def recordFailure(
      id: ProjectionId,
      errorMessage: ErrorMessage,
      f: Throwable => String = Projection.stackTraceAsString
  ): Task[Unit] = {
    logger.error(s"Recording error during projection {} at offset {}}", id, errorMessage.offset)
    def errorWrite(timestamp: Instant) = errorMessage match {
      case CastFailedMessage(offset, persistenceId, sequenceNr, expectedClassname, encounteredClassName) =>
        val error = s"Class $expectedClassname was expected, $encounteredClassName was encountered "
        sql"""INSERT INTO projections_failures (projection_id, akka_offset, timestamp, persistence_id, sequence_nr,
             |value, error_type, error)
             |VALUES (${id.value}, ${offsetToSequence(offset)}, ${timestamp.toEpochMilli}, $persistenceId,
             |$sequenceNr, null,
             |${ClassUtils.simpleName(errorMessage)}, $error)
             |ON CONFLICT DO NOTHING""".stripMargin.update.run.transact(xa).map(_ => ())
      case failureMessage: FailureMessage[A]                                                             =>
        sql"""INSERT INTO projections_failures (projection_id, akka_offset, timestamp, persistence_id, sequence_nr,
             |value, error_type, error)
             |VALUES (${id.value}, ${offsetToSequence(failureMessage.offset)}, ${timestamp.toEpochMilli}, ${failureMessage.persistenceId},
             |${failureMessage.sequenceNr}, ${failureMessage.value.asJson.noSpaces},
             |${ClassUtils.simpleName(failureMessage.throwable)}, ${f(failureMessage.throwable)})
             |ON CONFLICT DO NOTHING""".stripMargin.update.run.transact(xa).map(_ => ())
    }

    instant.flatMap(errorWrite)
  }

  /**
    * An event stream for all failures recorded for a projection.
    *
    * @param id the projection identifier
    * @return a source of the failed events
    */
  override def failures(id: ProjectionId): fs2.Stream[Task, ProjectionFailure[A]] =
    sql"""SELECT value, akka_offset, timestamp, error_type from projections_failures WHERE projection_id = ${id.value} ORDER BY ordering"""
      .query[(Option[String], Option[Long], Long, String)]
      .stream
      .transact(xa)
      .map { case (value, offset, timestamp, errorType) =>
        ProjectionFailure(
          offset.fold[Offset](NoOffset)(Sequence),
          Instant.ofEpochMilli(timestamp),
          value.flatMap(decode[A](_).toOption),
          errorType
        )
      }
}

object PostgresProjection {
  private val logger: Logger = Logger[PostgresProjection.type]
}
