package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingCleanup
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import monix.bio.UIO

class ElasticSearchIndexingCleanup(
    client: ElasticSearchClient,
    cache: ProgressesCache,
    projection: Projection[Unit]
) extends IndexingCleanup[IndexingElasticSearchView] {

  override def apply(view: ViewIndex[IndexingElasticSearchView]): UIO[Unit] =
    client
      .deleteIndex(idx(view))
      .attempt
      .void >> cache.remove(view.projectionId) >> projection.delete(view.projectionId).attempt.void

  private def idx(view: ViewIndex[_]): IndexLabel =
    IndexLabel.unsafe(view.index)
}
