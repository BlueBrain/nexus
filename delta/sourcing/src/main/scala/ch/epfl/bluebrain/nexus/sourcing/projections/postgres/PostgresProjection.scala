package ch.epfl.bluebrain.nexus.sourcing.projections.postgres

import akka.persistence.query.{NoOffset, Offset, Sequence}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionError.{ProjectionFailure, ProjectionWarning}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.Severity.{Failure, Warning}
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
private[projections] class PostgresProjection[A: Encoder: Decoder](
    xa: Transactor[Task],
    empty: => A,
    throwableToString: Throwable => String
)(implicit clock: Clock[UIO])
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
    */
  override def recordProgress(id: ProjectionId, progress: ProjectionProgress[A]): Task[Unit] =
    instant.flatMap { timestamp =>
      sql"""INSERT into projections_progress(projection_id, akka_offset, timestamp, processed, discarded, warnings, failed, value)
           |VALUES(${id.value}, ${offsetToSequence(progress.offset)}, ${timestamp.toEpochMilli}, ${progress.processed},
           |${progress.discarded}, ${progress.warnings}, ${progress.failed}, ${progress.value.asJson.noSpaces})
           |ON CONFLICT (projection_id) DO UPDATE SET akka_offset=EXCLUDED.akka_offset, timestamp=EXCLUDED.timestamp,
           |processed=EXCLUDED.processed, discarded=EXCLUDED.discarded, warnings=EXCLUDED.warnings, failed=EXCLUDED.failed, value=EXCLUDED.value""".stripMargin.update.run
        .transact(xa)
        .void
    }

  /**
    * Retrieves the progress for the specified projection projectionId. If there is no record of progress
    * the [[ProjectionProgress.NoProgress]] is returned.
    *
    * @param id an unique projectionId for a projection
    * @return a future progress value for the specified projection projectionId
    */
  override def progress(id: ProjectionId): Task[ProjectionProgress[A]] =
    sql"SELECT akka_offset, timestamp, processed, discarded, warnings, failed, value FROM projections_progress WHERE projection_id = ${id.value}"
      .query[(Option[Long], Long, Long, Long, Long, Long, String)]
      .option
      .transact(xa)
      .flatMap {
        case Some((offset, timestamp, processed, discarded, warnings, failed, value)) =>
          Task.fromEither(decode[A](value)).map { v =>
            ProjectionProgress(
              offset.fold[Offset](NoOffset)(Sequence),
              Instant.ofEpochMilli(timestamp),
              processed,
              discarded,
              warnings,
              failed,
              v
            )
          }
        case None                                                                     => Task.pure(NoProgress(empty))
      }

  override def recordWarnings(id: ProjectionId, message: SuccessMessage[A]): Task[Unit] =
    Task.when(message.warnings.nonEmpty) {
      instant.flatMap { timestamp =>
        sql"""INSERT INTO projections_errors (projection_id, akka_offset, timestamp, persistence_id, sequence_nr,
             |value, severity, error_type, message)
             |VALUES (${id.value}, ${offsetToSequence(message.offset)}, ${timestamp.toEpochMilli}, ${message.persistenceId},
             |${message.sequenceNr}, ${message.value.asJson.noSpaces}, ${Severity.Warning.toString},
             |null, ${message.warningMessage})
             |ON CONFLICT DO NOTHING""".stripMargin.update.run.transact(xa).void
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
      errorMessage: ErrorMessage
  ): Task[Unit] = {
    logger.error(s"Recording error during projection {} at offset {}}", id, errorMessage.offset)
    def errorWrite(timestamp: Instant) = errorMessage match {
      case c: CastFailedMessage =>
        sql"""INSERT INTO projections_errors (projection_id, akka_offset, timestamp, persistence_id, sequence_nr,
             |value, severity, error_type, message)
             |VALUES (${id.value}, ${offsetToSequence(c.offset)}, ${timestamp.toEpochMilli}, ${c.persistenceId},
             |${c.sequenceNr}, null, ${Severity.Failure.toString},
             |'ClassCastException', ${c.errorMessage})
             |ON CONFLICT DO NOTHING""".stripMargin.update.run.transact(xa).void
      case f: FailureMessage[A] =>
        sql"""INSERT INTO projections_errors (projection_id, akka_offset, timestamp, persistence_id, sequence_nr,
             |value, severity, error_type, message)
             |VALUES (${id.value}, ${offsetToSequence(f.offset)}, ${timestamp.toEpochMilli}, ${f.persistenceId},
             |${f.sequenceNr}, ${f.value.asJson.noSpaces}, ${Severity.Failure.toString},
             |${ClassUtils.simpleName(f.throwable)}, ${throwableToString(f.throwable)})
             |ON CONFLICT DO NOTHING""".stripMargin.update.run.transact(xa).void
    }

    instant.flatMap(errorWrite)
  }

  /**
    * An event stream for all failures recorded for a projection.
    *
    * @param id the projection identifier
    * @return a source of the failed events
    */
  override def errors(id: ProjectionId): fs2.Stream[Task, ProjectionError[A]] =
    sql"""SELECT value, akka_offset, timestamp, persistence_id, sequence_nr, severity, error_type, message from projections_errors WHERE projection_id = ${id.value} ORDER BY ordering"""
      .query[(Option[String], Option[Long], Long, String, Long, String, Option[String], String)]
      .stream
      .transact(xa)
      .map { case (value, offset, timestamp, persistenceId, sequenceNr, severity, errorType, message) =>
        val akkaOffset = offset.fold[Offset](NoOffset)(Sequence)
        val valueA     = value.flatMap(decode[A](_).toOption)
        val instant    = Instant.ofEpochMilli(timestamp)
        Severity.fromString(severity) match {
          case Warning =>
            ProjectionWarning(
              akkaOffset,
              instant,
              message,
              persistenceId,
              sequenceNr,
              valueA
            )
          case Failure =>
            ProjectionFailure(
              akkaOffset,
              instant,
              message,
              persistenceId,
              sequenceNr,
              valueA,
              errorType.getOrElse("Unknown")
            )
        }

      }
}

object PostgresProjection {
  private val logger: Logger = Logger[PostgresProjection.type]
}
