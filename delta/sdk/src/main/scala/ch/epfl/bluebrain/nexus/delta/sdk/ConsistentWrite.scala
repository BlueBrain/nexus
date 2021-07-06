package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ExecutionType.{Consistent, Performant}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ConsistentWriteFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

trait ConsistentWrite {

  /**
    * Perform a consistent write based on the `executionType`.
    *
    * @param project        the project in which the resource is located
    * @param res            the resource to perform the consistent write for
    * @param executionType  the execution type
    */
  def apply[R](project: ProjectRef, res: EventExchangeValue[_, _], executionType: ExecutionType)(implicit
      rejectionMapper: Mapper[ConsistentWriteFailed, R]
  ): IO[R, Unit] =
    executionType match {
      case Performant => IO.unit
      case Consistent =>
        execute(
          project,
          res
        ).mapError(rejectionMapper.to)
    }

  /**
    * Execute the consistent write.
    * @param project        the project in which the resource is located
    * @param res            the resource to perform the consistent write for
    */
  protected def execute(project: ProjectRef, res: EventExchangeValue[_, _]): IO[ConsistentWriteFailed, Unit]

}

object ConsistentWrite {

  /**
    * An instance of [[ConsistentWrite]] which executes other [[ConsistentWrite]]s in parallel.
    */
  final class AggregateConsistentWrite(private val internal: Seq[ConsistentWrite]) extends ConsistentWrite {

    override def execute(project: ProjectRef, res: EventExchangeValue[_, _]): IO[ConsistentWriteFailed, Unit] =
      internal.parTraverse(w => w.execute(project, res)).void
  }

  object AggregateConsistentWrite {
    def apply(internal: Seq[ConsistentWrite]): AggregateConsistentWrite = new AggregateConsistentWrite(internal)
  }

}
