package ch.epfl.bluebrain.nexus.sourcing

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.sourcing.projections.{FailureMessage, Projection, ProjectionId, ProjectionProgress}
import fs2.Stream
import monix.bio.Task

import scala.collection.mutable

/**
  * A In Memory projection for tests
  */
class InMemoryProjection[A] extends Projection[A] {

  private val progressMap = mutable.Map[ProjectionId, ProjectionProgress]()

  private val progressFailures = mutable.Map[ProjectionId, FailureMessage[A]]()

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
    * @param failureMessage the failure message to persist
    */
  override def recordFailure(id: ProjectionId, failureMessage: FailureMessage[A], f: Throwable => String): Task[Unit] =
    Task.pure(progressFailures += id -> failureMessage)

  /**
    * An event stream for all failures recorded for a projection.
    *
    * @param id the projection identifier
    * @return a source of the failed events
    */
  override def failures(id: ProjectionId): Stream[Task, (A, Offset, String)]                                          =
    Stream.emits(progressFailures.map { case (_, failure) =>
      (failure.value, failure.offset, failure.throwable.getMessage)
    }.toSeq)
}
