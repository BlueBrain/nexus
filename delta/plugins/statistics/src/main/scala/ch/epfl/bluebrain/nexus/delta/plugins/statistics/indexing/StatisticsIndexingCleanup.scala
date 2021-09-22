package ch.epfl.bluebrain.nexus.delta.plugins.statistics.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingCleanup
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import monix.bio.UIO

class StatisticsIndexingCleanup(client: ElasticSearchClient, cache: ProgressesCache)
    extends IndexingCleanup[StatisticsView] {

  // TODO: We might want to delete the projection row too, but deletion is not implemented in Projection
  override def apply(view: ViewIndex[StatisticsView]): UIO[Unit] =
    cache.remove(view.projectionId) >> client.deleteIndex(idx(view)).attempt.void

  private def idx(view: ViewIndex[_]): IndexLabel =
    IndexLabel.unsafe(view.index)
}
