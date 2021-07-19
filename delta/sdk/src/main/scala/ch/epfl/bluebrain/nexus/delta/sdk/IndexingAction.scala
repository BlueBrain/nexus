package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.Indexing.{Async, Sync}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.IndexingActionFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

trait IndexingAction {

  /**
    * Perform an indexing action based on the indexing parameter.
    *
    * @param project   the project in which the resource is located
    * @param res       the resource to perform the indexing action for
    * @param indexing  the execution type
    */
  def apply[R](project: ProjectRef, res: EventExchangeValue[_, _], indexing: Indexing)(implicit
      rejectionMapper: Mapper[IndexingActionFailed, R]
  ): IO[R, Unit] =
    indexing match {
      case Async => IO.unit
      case Sync  =>
        execute(
          project,
          res
        ).mapError(rejectionMapper.to)
    }

  /**
    * Execute the indexing action.
    * @param project        the project in which the resource is located
    * @param res            the resource to perform the indexing action for
    */
  protected def execute(project: ProjectRef, res: EventExchangeValue[_, _]): IO[IndexingActionFailed, Unit]

}

object IndexingAction {

  /**
    * An instance of [[IndexingAction]] which executes other [[IndexingAction]]s in parallel.
    */
  final class AggregateIndexingAction(private val internal: Seq[IndexingAction]) extends IndexingAction {

    override def execute(project: ProjectRef, res: EventExchangeValue[_, _]): IO[IndexingActionFailed, Unit] =
      internal.parTraverse(w => w.execute(project, res)).void
  }

  object AggregateIndexingAction {
    def apply(internal: Seq[IndexingAction]): AggregateIndexingAction = new AggregateIndexingAction(internal)
  }
}
