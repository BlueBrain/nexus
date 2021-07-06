package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ConsistentWriteFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.{ConsistentWrite, EventExchange, ExecutionType}
import ch.epfl.bluebrain.nexus.testkit.IORef
import monix.bio.{IO, UIO}

class ConsistentWriteDummy(private val values: IORef[Map[(ProjectRef, Iri, Long), ExecutionType]])
    extends ConsistentWrite {

  override def apply[R](project: ProjectRef, res: EventExchange.EventExchangeValue[_, _], executionType: ExecutionType)(
      implicit rejectionMapper: Mapper[ConsistentWriteFailed, R]
  ): IO[R, Unit] =
    values.update(_.updated((project, res.value.resource.id, res.value.resource.rev), executionType))

  override protected def execute(
      project: ProjectRef,
      res: EventExchange.EventExchangeValue[_, _]
  ): IO[ConsistentWriteFailed, Unit] = IO.unit

  def valueFor(projectRef: ProjectRef, id: Iri, rev: Long): UIO[Option[ExecutionType]] =
    values.get.map(_.get((projectRef, id, rev)))
}

object ConsistentWriteDummy {

  def apply(): ConsistentWriteDummy = new ConsistentWriteDummy(IORef.unsafe(Map.empty))
}
