package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.IORef
import monix.bio.UIO

class IndexingActionDummy(private val values: IORef[Map[(ProjectRef, Iri, Int), IndexingMode]]) extends IndexingAction {

  override def apply[A](project: ProjectRef, res: ResourceF[A], indexingMode: IndexingMode): UIO[Unit] =
    values.update(_.updated((project, res.id, res.rev), indexingMode))

  def valueFor(projectRef: ProjectRef, id: Iri, rev: Int): UIO[Option[IndexingMode]] =
    values.get.map(_.get((projectRef, id, rev)))
}

object IndexingActionDummy {

  def apply(): IndexingActionDummy = new IndexingActionDummy(IORef.unsafe(Map.empty))
}
