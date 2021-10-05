package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingCleanup
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import monix.bio.UIO

class BlazegraphIndexingCleanup(
    client: BlazegraphClient,
    cache: ProgressesCache,
    projection: Projection[Unit]
) extends IndexingCleanup[IndexingBlazegraphView] {

  override def apply(view: ViewIndex[IndexingBlazegraphView]): UIO[Unit] =
    client
      .deleteNamespace(view.index)
      .attempt
      .void >> cache.remove(view.projectionId) >> projection.delete(view.projectionId).attempt.void
}
