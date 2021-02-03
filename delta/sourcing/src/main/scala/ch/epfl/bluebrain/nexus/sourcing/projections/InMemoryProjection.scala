package ch.epfl.bluebrain.nexus.sourcing.projections

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionError.{ProjectionFailure, ProjectionWarning}
import fs2.Stream
import monix.bio.{Task, UIO}

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

  override def recordWarnings(id: ProjectionId, message: SuccessMessage[A]): Task[Unit] =
    instant.map(instant =>
      errors.updateWith(id) { cur =>
        val newError = ProjectionWarning(
          message.offset,
          instant,
          message.warningMessage,
          message.persistenceId,
          message.sequenceNr,
          Some(message.value)
        )
        Some(cur.fold(Vector[ProjectionError[A]](newError))(_ :+ newError))
      }
    ) >> Task.unit

  override def recordFailure(id: ProjectionId, errorMessage: ErrorMessage): Task[Unit] =
    instant.map(instant =>
      errors.updateWith(id) { cur =>
        val newError = errorMessage match {
          case f: FailureMessage[A] =>
            ProjectionFailure[A](
              errorMessage.offset,
              instant,
              throwableToString(f.throwable),
              errorMessage.persistenceId,
              errorMessage.sequenceNr,
              Some(f.value),
              ClassUtils.simpleName(f.throwable)
            )
          case c: CastFailedMessage =>
            ProjectionFailure[A](
              errorMessage.offset,
              instant,
              c.errorMessage,
              errorMessage.persistenceId,
              errorMessage.sequenceNr,
              None,
              "ClassCastException"
            )
        }
        Some(cur.fold(Vector[ProjectionError[A]](newError))(_ :+ newError))
      }
    ) >> Task.unit

  override def errors(id: ProjectionId): Stream[Task, ProjectionError[A]] =
    errors.get(id) match {
      case Some(vector) => Stream.fromIterator[Task](vector.iterator)
      case None         => Stream.empty
    }

}
