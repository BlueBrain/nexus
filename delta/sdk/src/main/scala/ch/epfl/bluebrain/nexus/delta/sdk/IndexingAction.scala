package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.UIO

// TODO Change when the new indexing process has been implemented
trait IndexingAction {

  /**
    * Perform an indexing action based on the indexing parameter.
    *
    * @param project
    *   the project in which the resource is located
    * @param res
    *   the resource to perform the indexing action for
    * @param indexingMode
    *   the execution type
    */
  def apply[A](project: ProjectRef, res: ResourceF[A], indexingMode: IndexingMode): UIO[Unit]

}

object IndexingAction {

  /**
    * An instance of [[IndexingAction]] which executes other [[IndexingAction]] s in parallel.
    */
  final class AggregateIndexingAction(private val internal: Seq[IndexingAction]) extends IndexingAction {
    override def apply[A](project: ProjectRef, res: ResourceF[A], indexingMode: IndexingMode): UIO[Unit] =
      internal.parTraverse(w => w(project, res, indexingMode)).void
  }

  object AggregateIndexingAction {
    def apply(internal: Seq[IndexingAction]): AggregateIndexingAction = new AggregateIndexingAction(internal)
  }
}
