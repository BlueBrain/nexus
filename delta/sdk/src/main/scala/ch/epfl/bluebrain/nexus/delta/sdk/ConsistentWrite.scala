package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ConsistentWriteFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

trait ConsistentWrite {

  def apply[R](project: ProjectRef, res: EventExchangeValue[_, _])(implicit rejectionMapper: Mapper[ConsistentWriteFailed, R]): IO[R, Unit]
}

object ConsistentWrite {

  final class CompositeConsistentWrite(private val internal: Seq[ConsistentWrite]) extends ConsistentWrite {

    override def apply[R](project: ProjectRef,res: EventExchangeValue[_, _])(implicit rejectionMapper: Mapper[ConsistentWriteFailed, R]): IO[R, Unit] =
      internal.parTraverse(w => w(project, res)).void
  }

}
