package ch.epfl.bluebrain.nexus.sourcing

import ch.epfl.bluebrain.nexus.sourcing.projections._
import fs2.Stream
import monix.bio.Task

import java.time.Instant
import scala.collection.mutable

/**
  * A In Memory projection for tests
  */
class InMemoryProjection[A] extends Projection[A] {

  private val progressMap = mutable.Map[ProjectionId, ProjectionProgress]()

  private val progressFailures = mutable.Map[ProjectionId, ErrorMessage]()

  /**
    * Records progress against a projection identifier.
    *
    * @param id       the projection identifier
    * @param progress the offset to record
    * @return a future () value
    */
  override def recordProgress(id: ProjectionId, progress: ProjectionProgress): Task[Unit] =
    Task.pure(progressMap += id -> progress)

  /**
    * Retrieves the progress for the specified projection projectionId. If there is no record of progress
    * the [[ProjectionProgress.NoProgress]] is returned.
    *
    * @param id an unique projectionId for a projection
    * @return a future progress value for the specified projection projectionId
    */
  override def progress(id: ProjectionId): Task[ProjectionProgress]                       =
    Task.pure(progressMap.getOrElse(id, ProjectionProgress.NoProgress))

  /**
    * Record a specific event against a index failures log projectionId.
    *
    * @param id             the project identifier
    * @param errorMessage the error message to persist
    */
  override def recordFailure(id: ProjectionId, errorMessage: ErrorMessage, f: Throwable => String): Task[Unit] =
    Task.pure(progressFailures += id -> errorMessage)

  /**
    * An event stream for all failures recorded for a projection.
    *
    * @param id the projection identifier
    * @return a source of the failed events
    */
  override def failures(id: ProjectionId): Stream[Task, ProjectionFailure[A]]                                  =
    Stream.emits(progressFailures.map { case (_, error) =>
      val (value, message) = error match {
        case c: CastFailedMessage =>
          None -> s"${c.expectedClassname} was expected, ${c.encounteredClassName} was encountered "
        case f: FailureMessage[A] => Some(f.value) -> f.throwable.getMessage
      }
      ProjectionFailure(error.offset, Instant.EPOCH, value, message)
    }.toSeq)
}
