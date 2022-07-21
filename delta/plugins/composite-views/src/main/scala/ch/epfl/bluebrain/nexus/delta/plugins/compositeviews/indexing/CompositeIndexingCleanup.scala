package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingCleanup
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import monix.bio.{IO, UIO}

class CompositeIndexingCleanup(
    esConfig: ExternalIndexingConfig,
    esClient: ElasticSearchClient,
    blazeConfig: ExternalIndexingConfig,
    blazeClient: BlazegraphClient,
    cache: ProgressesCache,
    projection: Projection[Unit]
) extends IndexingCleanup[CompositeView] {

  override def apply(view: ViewIndex[CompositeView]): UIO[Unit] =
    blazeClient.deleteNamespace(view.index).absorb.onErrorRestartIf(_ => true).attempt.void >>
      IO.traverse(view.value.projections.value) {
        case p: ElasticSearchProjection => esClient.deleteIndex(idx(p, view)).attempt.void
        case p: SparqlProjection        => blazeClient.deleteNamespace(ns(p, view)).attempt.void
      }.void >> IO
        .traverse(projectionIds(view)) { pId => cache.remove(pId) >> projection.delete(pId).attempt.void }
        .void

  private def projectionIds(view: ViewIndex[CompositeView])                                        =
    CompositeViews.projectionIds(view.value, view.rev).map { case (_, _, projectionId) => projectionId }

  private def idx(projection: ElasticSearchProjection, view: ViewIndex[CompositeView]): IndexLabel =
    CompositeViews.index(projection, view.value, view.rev, esConfig.prefix)

  private def ns(projection: SparqlProjection, view: ViewIndex[CompositeView]): String =
    CompositeViews.namespace(projection, view.value, view.rev, blazeConfig.prefix)
}
