package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import cats.effect.concurrent.Deferred
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.{ElasticSearchViewAggregate, ElasticSearchViewCache}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingCoordinator.{ElasticSearchIndexingController, ElasticSearchIndexingCoordinator}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.{ElasticSearchIndexingCleanup, ElasticSearchIndexingCoordinator, ElasticSearchIndexingStream, ElasticSearchOnEventInstant}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metric.ProjectEventMetricsStream
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.migration.MigrationV16ToV17
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, schema => viewsSchemaId, ElasticSearchViewEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.migration.Migration
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStreamAwake, IndexingStreamController, OnEventInstant}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ProjectsEventsInstantCollection
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.PipeConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionId, ProjectionProgress}
import ch.epfl.bluebrain.nexus.delta.sourcing.{DatabaseCleanup, EventLog}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.{Task, UIO}
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
      new ElasticSearchClient(client, cfg.base, cfg.maxIndexPathLength)(cfg.credentials, as.classicSystem)
  }

  make[IndexingSource].named("elasticsearch-source").from {
    (
        cfg: ElasticSearchViewsConfig,
        projects: Projects,
        eventLog: EventLog[Envelope[Event]],
        exchanges: Set[EventExchange]
    ) =>
      IndexingSource(
        projects,
        eventLog,
        exchanges,
        cfg.indexing.maxBatchSize,
        cfg.indexing.maxTimeWindow,
        cfg.indexing.retry
      )
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
        pipeConfig: PipeConfig,
        config: ElasticSearchViewsConfig,
        scheduler: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        base: BaseUri
    ) =>
      new ElasticSearchIndexingStream(client, indexingSource, cache, pipeConfig, config, projection)(
        cr,
        base,
        scheduler
      )
  }

  make[ElasticSearchIndexingController].from { (as: ActorSystem[Nothing]) =>
    new IndexingStreamController[IndexingElasticSearchView](ElasticSearchViews.moduleType)(as)
  }

  make[ElasticSearchIndexingCleanup].from {
    (
        client: ElasticSearchClient,
        cache: ProgressesCache @Id("elasticsearch-progresses"),
        projection: Projection[Unit]
    ) =>
      new ElasticSearchIndexingCleanup(client, cache, projection)
  }

  make[ElasticSearchIndexingCoordinator].fromEffect {
    (
        views: ElasticSearchViews,
        indexingController: ElasticSearchIndexingController,
        indexingCleanup: ElasticSearchIndexingCleanup,
        indexingStream: ElasticSearchIndexingStream,
        config: ElasticSearchViewsConfig,
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        uuidF: UUIDF
    ) =>
      ElasticSearchIndexingCoordinator(views, indexingController, indexingStream, indexingCleanup, config)(
        uuidF,
        as,
        scheduler
      )
  }

  make[ElasticSearchViewCache].fromEffect { (config: ElasticSearchViewsConfig, as: ActorSystem[Nothing]) =>
    ElasticSearchViews.cache(config)(as)
  }

  make[Deferred[Task, ElasticSearchViews]].fromEffect(Deferred[Task, ElasticSearchViews])

  make[ElasticSearchViewAggregate].fromEffect {
    (
        pipeConfig: PipeConfig,
        config: ElasticSearchViewsConfig,
        permissions: Permissions,
        client: ElasticSearchClient,
        deferred: Deferred[Task, ElasticSearchViews],
        resourceIdCheck: ResourceIdCheck,
        as: ActorSystem[Nothing],
        uuidF: UUIDF,
        clock: Clock[UIO]
    ) =>
      ElasticSearchViews.aggregate(pipeConfig, config, permissions, client, deferred, resourceIdCheck)(as, uuidF, clock)
  }

  make[ElasticSearchViews]
    .fromEffect {
      (
          cfg: ElasticSearchViewsConfig,
          log: EventLog[Envelope[ElasticSearchViewEvent]],
          contextResolution: ResolverContextResolution,
          cache: ElasticSearchViewCache,
          agg: ElasticSearchViewAggregate,
          deferred: Deferred[Task, ElasticSearchViews],
          orgs: Organizations,
          projects: Projects,
          api: JsonLdApi,
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          scheduler: Scheduler
      ) =>
        ElasticSearchViews(
          deferred,
          cfg,
          log,
          contextResolution,
          cache,
          agg,
          orgs,
          projects
        )(
          api,
          uuidF,
          scheduler,
          as
        )
    }

  many[ResourcesDeletion].add {
    (
        cache: ElasticSearchViewCache,
        agg: ElasticSearchViewAggregate,
        views: ElasticSearchViews,
        dbCleanup: DatabaseCleanup,
        coordinator: ElasticSearchIndexingCoordinator,
        indexingStreamAwake: IndexingStreamAwake
    ) => ElasticSearchViewsDeletion(cache, agg, views, dbCleanup, coordinator, indexingStreamAwake)
  }

  many[ProjectReferenceFinder].add { (views: ElasticSearchViews) =>
    ElasticSearchViews.projectReferenceFinder(views)
  }

  make[ElasticSearchViewsQuery].from {
    (
        acls: Acls,
        projects: Projects,
        views: ElasticSearchViews,
        cache: ElasticSearchViewCache,
        client: ElasticSearchClient,
        cfg: ElasticSearchViewsConfig
    ) =>
      ElasticSearchViewsQuery(acls, projects, views, cache, client)(cfg.indexing)
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

  make[Projection[ProjectsEventsInstantCollection]].fromEffect {
    (database: DatabaseConfig, system: ActorSystem[Nothing], clock: Clock[UIO]) =>
      Projection(database, ProjectsEventsInstantCollection.empty, system, clock)
  }

  make[IndexingStreamAwake].fromEffect {
    (
        cfg: ProjectsConfig,
        projection: Projection[ProjectsEventsInstantCollection],
        onEventInstantsSet: Set[OnEventInstant],
        eventLog: EventLog[Envelope[ProjectScopedEvent]],
        uuidF: UUIDF,
        as: ActorSystem[Nothing],
        sc: Scheduler
    ) =>
      IndexingStreamAwake(
        projection,
        eventLog.eventsByTag(Event.eventTag, _),
        OnEventInstant.combine(onEventInstantsSet),
        cfg.keyValueStore.retry,
        cfg.persistProgressConfig
      )(uuidF, as, sc)

  }

  make[ProjectEventMetricsStream].fromEffect {
    (
        eventLog: EventLog[Envelope[ProjectScopedEvent]],
        exchanges: Set[EventExchange],
        client: ElasticSearchClient,
        projection: Projection[Unit],
        config: ElasticSearchViewsConfig,
        uuidF: UUIDF,
        as: ActorSystem[Nothing],
        sc: Scheduler
    ) =>
      ProjectEventMetricsStream(
        eventLog.eventsByTag(Event.eventTag, _),
        exchanges,
        client,
        projection,
        config.indexing
      )(uuidF, as, sc)
  }

  make[ElasticSearchViewsRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        orgs: Organizations,
        projects: Projects,
        views: ElasticSearchViews,
        indexingAction: IndexingAction @Id("aggregate"),
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
        sseEventLog,
        indexingAction
      )(
        baseUri,
        cfg.pagination,
        s,
        cr,
        ordering
      )
  }

  make[ElasticSearchScopeInitialization]

  many[ScopeInitialization].ref[ElasticSearchScopeInitialization]

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/elasticsearch-metadata.json"))

  make[MetadataContextValue]
    .named("search-metadata")
    .from((agg: Set[MetadataContextValue]) => agg.foldLeft(MetadataContextValue.empty)(_ merge _))

  make[MetadataContextValue]
    .named("indexing-metadata")
    .from { (listingsMetadataCtx: MetadataContextValue @Id("search-metadata")) =>
      MetadataContextValue(listingsMetadataCtx.value.visit(obj = { case ContextObject(obj) =>
        ContextObject(obj.filterKeys(_.startsWith("_")))
      }))
    }

  many[RemoteContextResolution].addEffect {
    (
        searchMetadataCtx: MetadataContextValue @Id("search-metadata"),
        indexingMetadataCtx: MetadataContextValue @Id("indexing-metadata")
    ) =>
      for {
        elasticsearchCtx     <- ContextValue.fromFile("contexts/elasticsearch.json")
        elasticsearchMetaCtx <- ContextValue.fromFile("contexts/elasticsearch-metadata.json")
        elasticsearchIdxCtx  <- ContextValue.fromFile("contexts/elasticsearch-indexing.json")
        offsetCtx            <- ContextValue.fromFile("contexts/offset.json")
        statisticsCtx        <- ContextValue.fromFile("contexts/statistics.json")
      } yield RemoteContextResolution.fixed(
        contexts.elasticsearch         -> elasticsearchCtx,
        contexts.elasticsearchMetadata -> elasticsearchMetaCtx,
        contexts.elasticsearchIndexing -> elasticsearchIdxCtx,
        contexts.indexingMetadata      -> indexingMetadataCtx.value,
        contexts.searchMetadata        -> searchMetadataCtx.value,
        Vocabulary.contexts.offset     -> offsetCtx,
        Vocabulary.contexts.statistics -> statisticsCtx
      )
  }

  many[ResourceToSchemaMappings].add(
    ResourceToSchemaMappings(Label.unsafe("views") -> viewsSchemaId.iri)
  )

  many[ApiMappings].add(ElasticSearchViews.mappings)

  many[PriorityRoute].add { (route: ElasticSearchViewsRoutes) => PriorityRoute(priority, route.routes) }

  many[ServiceDependency].add { new ElasticSearchServiceDependency(_) }

  many[ReferenceExchange].add { (elasticSearchViews: ElasticSearchViews) =>
    ElasticSearchViews.referenceExchange(elasticSearchViews)
  }

  many[IndexingAction].add {
    (
        client: ElasticSearchClient,
        cache: ElasticSearchViewCache,
        pipeConfig: PipeConfig,
        config: ElasticSearchViewsConfig,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      new ElasticSearchIndexingAction(client, cache, pipeConfig, config)(cr, baseUri)
  }

  make[ElasticSearchViewEventExchange]
  many[EventExchange].named("view").ref[ElasticSearchViewEventExchange]
  many[EventExchange].named("resources").ref[ElasticSearchViewEventExchange]
  many[EventExchange].ref[ElasticSearchViewEventExchange]
  many[EntityType].add(EntityType(ElasticSearchViews.moduleType))
  make[ElasticSearchOnEventInstant]
  many[OnEventInstant].ref[ElasticSearchOnEventInstant]

  if (sys.env.contains("MIGRATION_1_7")) {
    make[Migration].fromEffect((as: ActorSystem[Nothing], databaseConfig: DatabaseConfig) =>
      MigrationV16ToV17(as, databaseConfig.cassandra)
    )
  }

}
