package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.{DeltaClient, RemoteSse}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingCoordinator.{CompositeIndexingController, CompositeIndexingCoordinator}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingStream.PartialRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.{CompositeIndexingCoordinator, CompositeIndexingStream, RemoteIndexingSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{contexts, CompositeView, CompositeViewEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes.CompositeViewsRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.rdf.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStreamBehaviour.Restart
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStreamController}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionId, ProjectionProgress}
import ch.epfl.bluebrain.nexus.migration.CompositeViewsMigration
import distage.ModuleDef
import izumi.distage.model.definition.Id
import monix.bio.UIO
import monix.execution.Scheduler

class CompositeViewsPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[CompositeViewsConfig].fromEffect { cfg => CompositeViewsConfig.load(cfg) }

  make[EventLog[Envelope[CompositeViewEvent]]].fromEffect { databaseEventLog[CompositeViewEvent](_, _) }

  make[DeltaClient].from { (cfg: CompositeViewsConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
    val httpClient = HttpClient()(cfg.remoteSourceClient.http, as.classicSystem, sc)
    DeltaClient(httpClient, cfg.remoteSourceClient.retryDelay)(as, sc)
  }

  make[CompositeViews].fromEffect {
    (
        config: CompositeViewsConfig,
        eventLog: EventLog[Envelope[CompositeViewEvent]],
        permissions: Permissions,
        orgs: Organizations,
        projects: Projects,
        acls: Acls,
        client: ElasticSearchClient,
        deltaClient: DeltaClient,
        contextResolution: ResolverContextResolution,
        uuidF: UUIDF,
        clock: Clock[UIO],
        as: ActorSystem[Nothing],
        sc: Scheduler,
        baseUri: BaseUri,
        crypto: Crypto
    ) =>
      CompositeViews(
        config,
        eventLog,
        permissions,
        orgs,
        projects,
        acls,
        client,
        deltaClient,
        contextResolution,
        crypto
      )(
        uuidF,
        clock,
        as,
        sc,
        baseUri
      )
  }

  make[IndexingSource].named("composite-source").from {
    (
        cfg: CompositeViewsConfig,
        eventLog: EventLog[Envelope[Event]],
        exchanges: Set[EventExchange]
    ) =>
      IndexingSource(eventLog, exchanges, cfg.sources.maxBatchSize, cfg.sources.maxTimeWindow)
  }

  make[ProgressesCache].named("composite-progresses").from { (cfg: CompositeViewsConfig, as: ActorSystem[Nothing]) =>
    KeyValueStore.distributed[ProjectionId, ProjectionProgress[Unit]](
      "composite-views-progresses",
      (_, v) => v.timestamp.toEpochMilli
    )(as, cfg.keyValueStore)
  }

  make[ProgressesStatistics].named("composite-statistics").from {
    (cache: ProgressesCache @Id("composite-progresses"), projectsCounts: ProjectsCounts) =>
      new ProgressesStatistics(cache, projectsCounts)
  }

  make[CompositeIndexingController].from { (as: ActorSystem[Nothing]) =>
    new IndexingStreamController[CompositeView](CompositeViews.moduleType)(as)
  }

  make[MetadataPredicates].fromEffect {
    (aggMetadataCtx: MetadataContextValue @Id("aggregated-metadata"), cr: RemoteContextResolution @Id("aggregate")) =>
      implicit val res = cr
      JsonLdContext(aggMetadataCtx.value)
        .map(_.aliasesInv.keySet.map(Triple.predicate))
        .map(MetadataPredicates)
  }

  make[RemoteIndexingSource].from {
    (deltaClient: DeltaClient, metadataPredicates: MetadataPredicates, config: CompositeViewsConfig) =>
      RemoteIndexingSource(
        deltaClient.events[RemoteSse],
        deltaClient.resourceAsNQuads,
        config.remoteSourceClient,
        metadataPredicates
      )
  }

  make[CompositeIndexingStream].from {
    (
        esClient: ElasticSearchClient,
        blazeClient: BlazegraphClient,
        projection: Projection[Unit],
        deltaClient: DeltaClient,
        indexingController: CompositeIndexingController,
        projectsCounts: ProjectsCounts,
        indexingSource: IndexingSource @Id("composite-source"),
        remoteIndexingSource: RemoteIndexingSource,
        cache: ProgressesCache @Id("composite-progresses"),
        config: CompositeViewsConfig,
        scheduler: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        base: BaseUri
    ) =>
      CompositeIndexingStream(
        config,
        esClient,
        blazeClient,
        deltaClient,
        cache,
        projectsCounts,
        indexingController,
        projection,
        indexingSource,
        remoteIndexingSource
      )(cr, base, scheduler)
  }

  make[CompositeIndexingCoordinator].fromEffect {
    (
        views: CompositeViews,
        indexingController: CompositeIndexingController,
        indexingStream: CompositeIndexingStream,
        config: CompositeViewsConfig,
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        uuidF: UUIDF
    ) =>
      CompositeIndexingCoordinator(views, indexingController, indexingStream, config)(uuidF, as, scheduler)
  }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/composite-views-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      ctx     <- ContextValue.fromFile("contexts/composite-views.json")
      metaCtx <- ContextValue.fromFile("contexts/composite-views-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.compositeViews         -> ctx,
      contexts.compositeViewsMetadata -> metaCtx
    )
  )

  make[BlazegraphQuery].from {
    (acls: Acls, views: CompositeViews, client: BlazegraphClient, cfg: CompositeViewsConfig) =>
      BlazegraphQuery(acls, views, client)(cfg.blazegraphIndexing)

  }

  make[ElasticSearchQuery].from {
    (acls: Acls, views: CompositeViews, client: ElasticSearchClient, cfg: CompositeViewsConfig) =>
      ElasticSearchQuery(acls, views, client)(cfg.elasticSearchIndexing)
  }

  make[CompositeViewsRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        projects: Projects,
        views: CompositeViews,
        indexingController: CompositeIndexingController,
        progresses: ProgressesStatistics @Id("composite-statistics"),
        blazegraphQuery: BlazegraphQuery,
        elasticSearchQuery: ElasticSearchQuery,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new CompositeViewsRoutes(
        identities,
        acls,
        projects,
        views,
        indexingController.restart,
        (iri, project, projections) => indexingController.restart(iri, project, Restart(PartialRestart(projections))),
        progresses,
        blazegraphQuery,
        elasticSearchQuery
      )(baseUri, s, cr, ordering)
  }

  make[CompositeViewsMigration].from { (views: CompositeViews) =>
    new CompositeViewsMigrationImpl(views)
  }

  many[PriorityRoute].add { (route: CompositeViewsRoutes) => PriorityRoute(priority, route.routes) }
  make[CompositeViewReferenceExchange]
  many[ReferenceExchange].ref[CompositeViewReferenceExchange]

  make[CompositeViewEventExchange]
  many[EventExchange].named("view").ref[CompositeViewEventExchange]
  many[EventExchange].ref[CompositeViewEventExchange]
}
