package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingEventLog.IndexingData
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.StreamBuilder
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.StreamBuilder.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream.{ChunkStreamOps, SimpleStreamOps}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionId}
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task}
import monix.execution.Scheduler

/**
  * Defines how to build a stream for an IndexingElasticSearchView
  */
final class ElasticSearchStreamBuilder(
    client: ElasticSearchClient,
    cache: ProgressesCache,
    config: ElasticSearchViewsConfig,
    eventLog: ElasticSearchIndexingEventLog,
    projection: Projection[Unit]
)(implicit cr: RemoteContextResolution, baseUri: BaseUri, sc: Scheduler)
    extends StreamBuilder[IndexingElasticSearchView] {

  private val ctx: ContextValue =
    ContextValue(contexts.elasticsearchIndexing, Vocabulary.contexts.metadataAggregate)

  override def apply(
      prev: Option[ViewIndex[IndexingElasticSearchView]],
      view: ViewIndex[IndexingElasticSearchView],
      progressStrategy: StreamBuilder.ProgressStrategy
  ): Stream[Task, Unit] = {
    implicit val projectionId: ProjectionId = view.projectionId
    Stream
      .eval {
        for {
          _        <- prev.fold(Task.unit)(prev => client.deleteIndex(idx(prev)).void >> cache.remove(prev.projectionId))
          _        <- client.createIndex(idx(view), Some(view.value.mapping), view.value.settings)
          progress <- progressStrategy match {
                        case ProgressStrategy.Continue    =>
                          projection.progress(projectionId)
                        case ProgressStrategy.FullRestart =>
                          cache.remove(projectionId) >>
                            cache.put(projectionId, NoProgress) >>
                            projection.recordProgress(projectionId, NoProgress).as(NoProgress)
                      }
        } yield progress
      }
      .flatMap { progress =>
        eventLog
          .stream(view.projectRef, progress.offset, view.value.resourceTag)
          .evalMapFilterValue {
            case res if containsSchema(view.value, res) && containsTypes(view.value, res) =>
              deleteOrIndex(view, res).map(Some.apply)
            case res if containsSchema(view.value, res)                                   =>
              delete(idx(view), res).map(Some.apply)
            case _                                                                        =>
              Task.none
          }
          .runAsyncUnit(bulk => IO.when(bulk.nonEmpty)(client.bulk(bulk)))
          .flatMap(Stream.chunk)
          .map(_.void)
          .persistProgressWithCache(
            progress,
            projection,
            cache.put,
            config.indexing.projection,
            config.indexing.cache
          )
      }
  }

  private def deleteOrIndex(
      view: ViewIndex[IndexingElasticSearchView],
      res: ResourceF[IndexingData]
  ): Task[ElasticSearchBulk] = {
    if (res.deprecated && !view.value.includeDeprecated) delete(idx(view), res)
    else index(idx(view), view.value, res)
  }

  private def delete(index: IndexLabel, res: ResourceF[IndexingData]): Task[ElasticSearchBulk] =
    Task.pure(ElasticSearchBulk.Delete(index, res.id.toString))

  private def index(
      index: IndexLabel,
      view: IndexingElasticSearchView,
      res: ResourceF[IndexingData]
  ): Task[ElasticSearchBulk] =
    toDocument(view, res).map(doc => ElasticSearchBulk.Index(index, res.id.toString, doc))

  private def toDocument(view: IndexingElasticSearchView, res: ResourceF[IndexingData]): Task[Json] = {
    val predGraph = res.value.selectPredicatesGraph
    val metaGraph = res.value.metadataGraph
    Option
      .when(view.includeMetadata)(res.void.toGraph.map(_ ++ predGraph ++ metaGraph))
      .getOrElse(Task.pure(predGraph))
      .flatMap {
        case graph if view.sourceAsText =>
          val jsonLd = graph.add(nxv.originalSource.iri, res.value.source.noSpaces).toCompactedJsonLd(ctx)
          jsonLd.map(_.json.removeKeys(keywords.context))
        case graph                      =>
          val jsonLd = graph.toCompactedJsonLd(ctx)
          jsonLd.map(ld => res.value.source deepMerge ld.json).map(_.removeAllKeys(keywords.context))
      }
  }

  private def containsSchema[A](view: IndexingElasticSearchView, res: ResourceF[A]): Boolean =
    view.resourceSchemas.isEmpty || view.resourceSchemas.contains(res.schema.iri)

  private def containsTypes[A](view: IndexingElasticSearchView, res: ResourceF[A]): Boolean =
    view.resourceTypes.isEmpty || view.resourceTypes.intersect(res.types).nonEmpty

  private def idx(view: ViewIndex[IndexingElasticSearchView]): IndexLabel =
    IndexLabel.unsafe(view.index)
}
