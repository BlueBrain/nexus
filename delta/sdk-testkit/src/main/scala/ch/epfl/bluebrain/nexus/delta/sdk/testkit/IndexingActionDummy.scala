package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.IndexingActionFailed
import ch.epfl.bluebrain.nexus.delta.sdk.{EventExchange, IndexingAction, IndexingMode}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.IORef
import monix.bio.{IO, UIO}

class IndexingActionDummy(private val values: IORef[Map[(ProjectRef, Iri, Long), IndexingMode]])
    extends IndexingAction {

  override def apply[R](project: ProjectRef, res: EventExchange.EventExchangeValue[_, _], indexingMode: IndexingMode)(
      implicit rejectionMapper: Mapper[IndexingActionFailed, R]
  ): IO[R, Unit] =
    values.update(_.updated((project, res.value.resource.id, res.value.resource.rev), indexingMode))

  override protected def execute(
      project: ProjectRef,
      res: EventExchange.EventExchangeValue[_, _]
  ): IO[IndexingActionFailed, Unit] = IO.unit

  def valueFor(projectRef: ProjectRef, id: Iri, rev: Long): UIO[Option[IndexingMode]] =
    values.get.map(_.get((projectRef, id, rev)))
}

object IndexingActionDummy {

  def apply(): IndexingActionDummy = new IndexingActionDummy(IORef.unsafe(Map.empty))
}
