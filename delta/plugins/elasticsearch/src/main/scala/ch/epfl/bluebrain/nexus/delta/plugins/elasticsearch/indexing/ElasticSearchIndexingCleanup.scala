package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingCleanup
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import monix.bio.UIO

class ElasticSearchIndexingCleanup(
    client: ElasticSearchClient,
    cache: ProgressesCache
) extends IndexingCleanup[IndexingElasticSearchView] {

  // TODO: We might want to delete the projection row too, but deletion is not implemented in Projection
  override def apply(view: ViewIndex[IndexingElasticSearchView]): UIO[Unit] =
    cache.remove(view.projectionId) >> client.deleteIndex(idx(view)).attempt.void

  private def idx(view: ViewIndex[_]): IndexLabel =
    IndexLabel.unsafe(view.index)
}
