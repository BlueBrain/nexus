package ch.epfl.bluebrain.nexus.sourcing.projections

import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionError.{ProjectionFailure, ProjectionWarning}
import fs2.Stream
import monix.bio.{Task, UIO}
import java.time.Instant

import scala.collection.concurrent.{Map => ConcurrentMap}

class InMemoryProjection[A](
    empty: => A,
    throwableToString: Throwable => String,
    success: ConcurrentMap[ProjectionId, ProjectionProgress[A]],
    errors: ConcurrentMap[ProjectionId, Vector[ProjectionError[A]]]
)(implicit clock: Clock[UIO])
    extends Projection[A] {

  override def recordProgress(id: ProjectionId, progress: ProjectionProgress[A]): Task[Unit] =
    Task.delay(success.update(id, progress))

  override def progress(id: ProjectionId): Task[ProjectionProgress[A]] =
    Task.delay(success.getOrElse(id, ProjectionProgress.NoProgress(empty)))

  private def batch(timestamp: Instant, messages: Vector[Message[A]]): Vector[ProjectionError[A]] =
    messages.mapFilter {
      case c: CastFailedMessage =>
        Some(
          ProjectionFailure[A](
            c.offset,
            timestamp,
            c.errorMessage,
            c.persistenceId,
            c.sequenceNr,
            None,
            "ClassCastException"
          )
        )
      case f: FailureMessage[A] =>
        Some(
          ProjectionFailure[A](
            f.offset,
            timestamp,
            throwableToString(f.throwable),
            f.persistenceId,
            f.sequenceNr,
            Some(f.value),
            ClassUtils.simpleName(f.throwable)
          )
        )

      case w: SuccessMessage[A] if w.warnings.nonEmpty =>
        Some(
          ProjectionWarning(
            w.offset,
            timestamp,
            w.warningMessage,
            w.persistenceId,
            w.sequenceNr,
            Some(w.value)
          )
        )
      case _                                           => None

    }

  override def recordErrors(id: ProjectionId, messages: Vector[Message[A]]): Task[Unit] =
    instant.map(instant =>
      errors.updateWith(id) { cur =>
        val batchErrors = batch(instant, messages)
        Some(cur.fold(batchErrors)(_ ++ batchErrors))
      }
    ) >> Task.unit

  override def errors(id: ProjectionId): Stream[Task, ProjectionError[A]] =
    errors.get(id) match {
      case Some(vector) => Stream.fromIterator[Task](vector.iterator)
      case None         => Stream.empty
    }

}
