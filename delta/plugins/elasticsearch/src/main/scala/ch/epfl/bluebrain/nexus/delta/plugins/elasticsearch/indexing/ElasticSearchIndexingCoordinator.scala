package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.actor.typed.ActorSystem
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingEventLog.IndexingData
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, IndexingViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamCoordinator
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream.{ChunkStreamOps, SimpleStreamOps}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task}
import monix.execution.Scheduler

private class IndexingStream(
    client: ElasticSearchClient,
    cache: ProgressesCache,
    viewRes: IndexingViewResource,
    config: ElasticSearchViewsConfig
)(implicit cr: RemoteContextResolution, baseUri: BaseUri) {
  implicit private val indexCfg: ExternalIndexingConfig = config.indexing
  private val view: IndexingElasticSearchView           = viewRes.value
  private val index: IndexLabel                         = IndexLabel.unsafe(viewRes.index)
  private val ctx: ContextValue                         = ContextValue(contexts.elasticsearchIndexing, Vocabulary.contexts.metadataAggregate)
  implicit private val projectionId: ViewProjectionId   = viewRes.projectionId

  private def deleteOrIndex(res: ResourceF[IndexingData]): Task[ElasticSearchBulk] =
    if (res.deprecated && !view.includeDeprecated) delete(res)
    else index(res)

  private def delete(res: ResourceF[IndexingData]): Task[ElasticSearchBulk] =
    Task.pure(ElasticSearchBulk.Delete(index, res.id.toString))

  private def index(res: ResourceF[IndexingData]): Task[ElasticSearchBulk] =
    toDocument(res).map(doc => ElasticSearchBulk.Index(index, res.id.toString, doc))

  private def toDocument(res: ResourceF[IndexingData]): Task[Json] = {
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

  private def containsSchema[A](res: ResourceF[A]): Boolean =
    view.resourceSchemas.isEmpty || view.resourceSchemas.contains(res.schema.iri)

  private def containsTypes[A](res: ResourceF[A]): Boolean =
    view.resourceTypes.isEmpty || view.resourceTypes.intersect(res.types).nonEmpty

  def build(
      eventLog: ElasticSearchIndexingEventLog,
      projection: Projection[Unit],
      initialProgress: ProjectionProgress[Unit]
  )(implicit sc: Scheduler): Task[Stream[Task, Unit]] =
    for {
      _     <- client.createIndex(index, Some(view.mapping), view.settings)
      _     <- cache.remove(projectionId)
      _     <- cache.put(projectionId, initialProgress)
      stream = eventLog
                 .stream(view.project, initialProgress.offset, view.resourceTag)
                 .evalMapFilterValue {
                   case res if containsSchema(res) && containsTypes(res) => deleteOrIndex(res).map(Some.apply)
                   case res if containsSchema(res)                       => delete(res).map(Some.apply)
                   case _                                                => Task.pure(None)
                 }
                 .runAsyncUnit(bulk => IO.when(bulk.nonEmpty)(client.bulk(bulk)))
                 .flatMap(Stream.chunk)
                 .map(_.void)
                 .persistProgressWithCache(
                   initialProgress,
                   projection,
                   cache.put,
                   config.indexing.projection,
                   config.indexing.cache
                 )
    } yield stream
}

object ElasticSearchIndexingCoordinator {

  type StartCoordinator                 = IndexingViewResource => Task[Unit]
  type StopCoordinator                  = IndexingViewResource => Task[Unit]
  type ElasticSearchIndexingCoordinator = IndexingStreamCoordinator[IndexingViewResource]

  private val logger: Logger = Logger[ElasticSearchIndexingCoordinator.type]

  /**
    * Create a coordinator for indexing documents into ElasticSearch indices triggered and customized by the ElasticSearchViews.
    */
  def apply(
      eventLog: ElasticSearchIndexingEventLog,
      client: ElasticSearchClient,
      projection: Projection[Unit],
      cache: ProgressesCache,
      config: ElasticSearchViewsConfig
  )(implicit
      as: ActorSystem[Nothing],
      scheduler: Scheduler,
      cr: RemoteContextResolution,
      base: BaseUri
  ): Task[ElasticSearchIndexingCoordinator] = {

    val retryStrategy = RetryStrategy.retryOnNonFatal(config.indexing.retry, logger, "elasticsearch indexing")

    implicit val indexCfg: ExternalIndexingConfig = config.indexing

    IndexingStreamCoordinator[ResourceF[IndexingElasticSearchView]](
      "ElasticSearchViewsCoordinator",
      (res, progress) => new IndexingStream(client, cache, res, config).build(eventLog, projection, progress),
      index => client.deleteIndex(IndexLabel.unsafe(index)).void,
      projection,
      config.aggregate.processor,
      retryStrategy
    )
  }

}
