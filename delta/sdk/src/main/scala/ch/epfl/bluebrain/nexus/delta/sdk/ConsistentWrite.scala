package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.ConsistentWrite.ConsistentWriteValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ConsistentWriteFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

trait ConsistentWrite {

  def apply[R](res: ConsistentWriteValue[_, _])(implicit rejectionMapper: Mapper[ConsistentWriteFailed, R]): IO[R, Unit]
}

object ConsistentWrite {
  final case class ConsistentWriteValue[A, M](
      project: ProjectRef,
      value: ReferenceExchangeValue[A],
      metadata: JsonLdValue.Aux[M]
  )

  final class CompositeConsistentWrite(val internal: Seq[ConsistentWrite]) extends ConsistentWrite {

    override def apply[R](res: ConsistentWriteValue[_, _])(implicit rejectionMapper: Mapper[ConsistentWriteFailed, R]): IO[R, Unit] =
      internal.map(w => w(res)).parSequence.void
  }

}
