package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingMode.{Async, Sync}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.IndexingActionFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

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
  def apply[A, AA, R](project: ProjectRef, res: ResourceF[A], indexingMode: IndexingMode)(implicit
      eventExchangeValueMapper: Mapper[ResourceF[A], EventExchangeValue[A, AA]],
      rejectionMapper: Mapper[IndexingActionFailed, R]
  ): IO[R, Unit] =
    apply(project, eventExchangeValueMapper.to(res), indexingMode)

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
  def apply[R](
      project: ProjectRef,
      res: EventExchangeValue[_, _],
      indexingMode: IndexingMode
  )(implicit rejectionMapper: Mapper[IndexingActionFailed, R]): IO[R, Unit] =
    indexingMode match {
      case Async => IO.unit
      case Sync  => execute(project, res).mapError(rejectionMapper.to)
    }

  /**
    * Execute the indexing action.
    * @param project
    *   the project in which the resource is located
    * @param res
    *   the resource to perform the indexing action for
    */
  protected def execute(project: ProjectRef, res: EventExchangeValue[_, _]): IO[IndexingActionFailed, Unit]

}

object IndexingAction {

  /**
    * An instance of [[IndexingAction]] which executes other [[IndexingAction]] s in parallel.
    */
  final class AggregateIndexingAction(private val internal: Seq[IndexingAction]) extends IndexingAction {

    override def execute(project: ProjectRef, res: EventExchangeValue[_, _]): IO[IndexingActionFailed, Unit] =
      internal.parTraverse(w => w.execute(project, res)).void
  }

  object AggregateIndexingAction {
    def apply(internal: Seq[IndexingAction]): AggregateIndexingAction = new AggregateIndexingAction(internal)
  }
}
