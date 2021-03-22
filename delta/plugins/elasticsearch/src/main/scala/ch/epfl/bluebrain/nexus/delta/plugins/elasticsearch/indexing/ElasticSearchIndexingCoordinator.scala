package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.actor.typed.ActorSystem
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingEventLog.IndexingData
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.DifferentElasticSearchViewType
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, ElasticSearchViewEvent}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.MigrationState
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamCoordinator
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.views.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream.{ChunkStreamOps, SimpleStreamOps}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

private class IndexingStream(
    client: ElasticSearchClient,
    cache: ProgressesCache,
    viewIndex: ViewIndex[IndexingElasticSearchView],
    config: ElasticSearchViewsConfig
)(implicit cr: RemoteContextResolution, baseUri: BaseUri) {
  private val view: IndexingElasticSearchView         = viewIndex.underlyingView
  private val index: IndexLabel                       = IndexLabel.unsafe(viewIndex.index)
  private val ctx: ContextValue                       = ContextValue(contexts.elasticsearchIndexing, Vocabulary.contexts.metadataAggregate)
  implicit private val projectionId: ViewProjectionId = viewIndex.projectionId

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

  type ElasticSearchIndexingCoordinator = IndexingStreamCoordinator[IndexingElasticSearchView]

  private val logger: Logger = Logger[ElasticSearchIndexingCoordinator.type]

  private def fetchView(views: ElasticSearchViews, config: ElasticSearchViewsConfig) = (id: Iri, project: ProjectRef) =>
    views
      .fetchIndexingView(id, project)
      .flatMap { res =>
        UIO.pure(
          Some(
            ViewIndex(
              res.value.project,
              res.id,
              ElasticSearchViews.projectionId(res),
              ElasticSearchViews.index(res, config.indexing),
              res.rev,
              res.deprecated,
              res.value
            )
          )
        )
      }
      .onErrorHandle {
        case _: DifferentElasticSearchViewType =>
          logger.debug(s"Filtering out aggregate view from ")
          None
        case r                                 =>
          logger.error(
            s"While attempting to start indexing view $id in project $project, the rejection $r was encountered"
          )
          None
      }

  /**
    * Create a coordinator for indexing documents into ElasticSearch indices triggered and customized by the ElasticSearchViews.
    */
  def apply(
      views: ElasticSearchViews,
      indexingLog: ElasticSearchIndexingEventLog,
      client: ElasticSearchClient,
      projection: Projection[Unit],
      cache: ProgressesCache,
      config: ElasticSearchViewsConfig
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler,
      cr: RemoteContextResolution,
      base: BaseUri
  ): Task[ElasticSearchIndexingCoordinator] = Task
    .delay {
      val retryStrategy = RetryStrategy.retryOnNonFatal(config.indexing.retry, logger, "elasticsearch indexing")

      new IndexingStreamCoordinator[IndexingElasticSearchView](
        ElasticSearchViews.moduleType,
        fetchView(views, config),
        (res, progress) => new IndexingStream(client, cache, res, config).build(indexingLog, projection, progress),
        index => client.deleteIndex(IndexLabel.unsafe(index)).void,
        projection,
        retryStrategy
      )
    }
    .tapEval { coordinator =>
      def onEvent(event: ElasticSearchViewEvent) = coordinator.run(event.id, event.project, event.rev)
      IO.unless(MigrationState.isIndexingDisabled)(
        ElasticSearchViewsIndexing("ElasticSearchIndexingCoordinatorScan", config.indexing, views, onEvent)
      )
    }

}
