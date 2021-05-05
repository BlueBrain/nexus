package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingCleanup
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import monix.bio.UIO

class BlazegraphIndexingCleanup(
    client: BlazegraphClient,
    cache: ProgressesCache
) extends IndexingCleanup[IndexingBlazegraphView] {

  // TODO: We might want to delete the projection row too, but deletion is not implemented in Projection
  override def apply(view: ViewIndex[IndexingBlazegraphView]): UIO[Unit] =
    cache.remove(view.projectionId) >> client.deleteNamespace(view.index).attempt.void
}
