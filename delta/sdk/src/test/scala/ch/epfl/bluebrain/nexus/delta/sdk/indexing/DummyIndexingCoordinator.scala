package ch.epfl.bluebrain.nexus.delta.sdk.indexing

import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.DummyIndexingCoordinator.CoordinatorCounts
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId
import monix.bio.{Task, UIO}

/**
  * @param counts a cache of counts for each projection progress id
  */
class DummyIndexingCoordinator[V: ViewLens](counts: Ref[Task, Map[ProjectionId, CoordinatorCounts]])
    extends IndexingStreamCoordinator[V] {
  override def start(view: V): UIO[Unit] =
    counts
      .update(_.updatedWith(view.projectionId)(opt => Some(opt.getOrElse(CoordinatorCounts.empty).incrementStart)))
      .hideErrors

  /**
    * Restarts indexing the passed ''view'' from the beginning
    */
  override def restart(view: V): UIO[Unit] =
    counts
      .update(
        _.updatedWith(view.projectionId)(opt => Some(opt.getOrElse(CoordinatorCounts.empty).incrementRestart))
      )
      .hideErrors

  /**
    * Stop indexing the passed ''view''
    */
  override def stop(view: V): UIO[Unit] =
    counts
      .update(_.updatedWith(view.projectionId)(opt => Some(opt.getOrElse(CoordinatorCounts.empty).incrementStop)))
      .hideErrors

}

object DummyIndexingCoordinator {

  /**
    * Counts through the lifecycle of an indexing stream
    *
    * @param start   how many times start has been called on an indexing stream
    * @param restart how many times restart has been called on an indexing stream
    * @param stop    how many times stop has been called on an indexing stream
    */
  final case class CoordinatorCounts(start: Int, restart: Int, stop: Int) {
    def incrementStart: CoordinatorCounts   = copy(start = start + 1)
    def incrementRestart: CoordinatorCounts = copy(restart = restart + 1)
    def incrementStop: CoordinatorCounts    = copy(stop = stop + 1)
  }

  object CoordinatorCounts {
    val empty: CoordinatorCounts = CoordinatorCounts(0, 0, 0)
  }
}
