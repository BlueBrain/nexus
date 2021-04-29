package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingCoordinator.{ElasticSearchIndexingController, ElasticSearchIndexingCoordinator}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.{ElasticSearchIndexingCoordinator, ElasticSearchIndexingStream}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewEvent, contexts, schema => viewsSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStreamController}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionId, ProjectionProgress}
import ch.epfl.bluebrain.nexus.migration.ElasticSearchViewsMigration
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * ElasticSearch plugin wiring.
  */
class ElasticSearchPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[ElasticSearchViewsConfig].from { ElasticSearchViewsConfig.load(_) }

  make[EventLog[Envelope[ElasticSearchViewEvent]]].fromEffect { databaseEventLog[ElasticSearchViewEvent](_, _) }

  make[HttpClient].named("elasticsearch-client").from {
    (cfg: ElasticSearchViewsConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
      HttpClient()(cfg.client, as.classicSystem, sc)
  }

  make[ElasticSearchClient].from {
    (cfg: ElasticSearchViewsConfig, client: HttpClient @Id("elasticsearch-client"), as: ActorSystem[Nothing]) =>
      new ElasticSearchClient(client, cfg.base)(as.classicSystem)
  }

  make[IndexingSource].named("elasticsearch-source").from {
    (
        cfg: ElasticSearchViewsConfig,
        eventLog: EventLog[Envelope[Event]],
        exchanges: Set[EventExchange]
    ) =>
      IndexingSource(eventLog, exchanges, cfg.indexing.maxBatchSize, cfg.indexing.maxTimeWindow, cfg.indexing.retry)
  }

  make[ProgressesCache].named("elasticsearch-progresses").from {
    (cfg: ElasticSearchViewsConfig, as: ActorSystem[Nothing]) =>
      KeyValueStore.distributed[ProjectionId, ProjectionProgress[Unit]](
        "elasticsearch-views-progresses",
        (_, v) => v.timestamp.toEpochMilli
      )(as, cfg.keyValueStore)
  }

  make[ElasticSearchIndexingStream].from {
    (
        client: ElasticSearchClient,
        projection: Projection[Unit],
        indexingSource: IndexingSource @Id("elasticsearch-source"),
        cache: ProgressesCache @Id("elasticsearch-progresses"),
        config: ElasticSearchViewsConfig,
        scheduler: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        base: BaseUri
    ) =>
      new ElasticSearchIndexingStream(client, indexingSource, cache, config, projection)(cr, base, scheduler)
  }

  make[ElasticSearchIndexingController].from { (as: ActorSystem[Nothing]) =>
    new IndexingStreamController[IndexingElasticSearchView](ElasticSearchViews.moduleType)(as)
  }

  make[ElasticSearchIndexingCoordinator].fromEffect {
    (
        views: ElasticSearchViews,
        indexingController: ElasticSearchIndexingController,
        indexingStream: ElasticSearchIndexingStream,
        config: ElasticSearchViewsConfig,
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        uuidF: UUIDF
    ) =>
      ElasticSearchIndexingCoordinator(views, indexingController, indexingStream, config)(uuidF, as, scheduler)
  }

  make[ElasticSearchViews]
    .fromEffect {
      (
          cfg: ElasticSearchViewsConfig,
          log: EventLog[Envelope[ElasticSearchViewEvent]],
          contextResolution: ResolverContextResolution,
          client: ElasticSearchClient,
          permissions: Permissions,
          orgs: Organizations,
          projects: Projects,
          clock: Clock[UIO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          scheduler: Scheduler
      ) =>
        ElasticSearchViews(cfg, log, contextResolution, orgs, projects, permissions, client)(
          uuidF,
          clock,
          scheduler,
          as
        )
    }

  make[ElasticSearchViewsQuery].from {
    (
        acls: Acls,
        projects: Projects,
        views: ElasticSearchViews,
        client: ElasticSearchClient,
        cfg: ElasticSearchViewsConfig
    ) =>
      ElasticSearchViewsQuery(acls, projects, views, client)(cfg.indexing)
  }

  make[ProgressesStatistics].named("elasticsearch-statistics").from {
    (cache: ProgressesCache @Id("elasticsearch-progresses"), projectsCounts: ProjectsCounts) =>
      new ProgressesStatistics(cache, projectsCounts)
  }

  make[SseEventLog]
    .named("view-sse")
    .from(
      (
          eventLog: EventLog[Envelope[Event]],
          orgs: Organizations,
          projects: Projects,
          exchanges: Set[EventExchange] @Id("view")
      ) => SseEventLog(eventLog, orgs, projects, exchanges, ElasticSearchViews.moduleTag)
    )

  make[ElasticSearchViewsRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        orgs: Organizations,
        projects: Projects,
        views: ElasticSearchViews,
        viewsQuery: ElasticSearchViewsQuery,
        progresses: ProgressesStatistics @Id("elasticsearch-statistics"),
        indexingController: ElasticSearchIndexingController,
        baseUri: BaseUri,
        cfg: ElasticSearchViewsConfig,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        resourcesToSchemaSet: Set[ResourceToSchemaMappings],
        sseEventLog: SseEventLog @Id("view-sse")
    ) =>
      val resourceToSchema = resourcesToSchemaSet.foldLeft(ResourceToSchemaMappings.empty)(_ + _)
      new ElasticSearchViewsRoutes(
        identities,
        acls,
        orgs,
        projects,
        views,
        viewsQuery,
        progresses,
        indexingController.restart,
        resourceToSchema,
        sseEventLog
      )(
        baseUri,
        cfg.pagination,
        s,
        cr,
        ordering
      )
  }

  make[ElasticSearchScopeInitialization]

  make[ElasticSearchViewsMigration].from { (elasticSearchViews: ElasticSearchViews) =>
    new ElasticSearchViewsMigrationImpl(elasticSearchViews)
  }

  many[ScopeInitialization].ref[ElasticSearchScopeInitialization]

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/elasticsearch-metadata.json"))

  many[RemoteContextResolution].addEffect {
    for {
      elasticsearchCtx     <- ContextValue.fromFile("contexts/elasticsearch.json")
      elasticsearchMetaCtx <- ContextValue.fromFile("contexts/elasticsearch-metadata.json")
      elasticsearchIdxCtx  <- ContextValue.fromFile("contexts/elasticsearch-indexing.json")
    } yield RemoteContextResolution.fixed(
      contexts.elasticsearch         -> elasticsearchCtx,
      contexts.elasticsearchMetadata -> elasticsearchMetaCtx,
      contexts.elasticsearchIndexing -> elasticsearchIdxCtx
    )
  }

  many[ResourceToSchemaMappings].add(
    ResourceToSchemaMappings(Label.unsafe("views") -> viewsSchemaId.iri)
  )

  many[ApiMappings].add(ElasticSearchViews.mappings)

  many[PriorityRoute].add { (route: ElasticSearchViewsRoutes) => PriorityRoute(priority, route.routes) }

  many[ServiceDependency].add { new ElasticSearchServiceDependency(_) }

  make[ElasticSearchViewReferenceExchange]
  many[ReferenceExchange].ref[ElasticSearchViewReferenceExchange]

  make[ElasticSearchViewEventExchange]
  many[EventExchange].named("view").ref[ElasticSearchViewEventExchange]
  many[EventExchange].ref[ElasticSearchViewEventExchange]
}
