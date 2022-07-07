package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.{IndexingAction, IndexingMode}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.UIO

class BlazegraphIndexingAction() extends IndexingAction {

  override def apply[A](project: ProjectRef, res: ResourceF[A], indexingMode: IndexingMode): UIO[Unit] = UIO.unit
}
