package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCoordinator.BlazegraphIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.{BlazegraphIndexingCoordinator, BlazegraphIndexingEventLog}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, BlazegraphViewsConfig, contexts, schema => viewsSchemaId}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Event, _}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionId, ProjectionProgress}
import ch.epfl.bluebrain.nexus.migration.BlazegraphViewsMigration
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Blazegraph plugin wiring
  */
class BlazegraphPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[BlazegraphViewsConfig].from { BlazegraphViewsConfig.load(_) }

  make[EventLog[Envelope[BlazegraphViewEvent]]].fromEffect { databaseEventLog[BlazegraphViewEvent](_, _) }

  make[HttpClient].named("blazegraph-client").from {
    (cfg: BlazegraphViewsConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
      HttpClient()(cfg.client, as.classicSystem, sc)
  }

  make[BlazegraphClient].from {
    (cfg: BlazegraphViewsConfig, client: HttpClient @Id("blazegraph-client"), as: ActorSystem[Nothing]) =>
      BlazegraphClient(client, cfg.base, cfg.credentials)(as.classicSystem)
  }

  make[BlazegraphIndexingEventLog].from {
    (
        cfg: BlazegraphViewsConfig,
        eventLog: EventLog[Envelope[Event]],
        exchanges: Set[EventExchange],
        rcr: RemoteContextResolution @Id("aggregate")
    ) =>
      BlazegraphIndexingEventLog(
        eventLog,
        exchanges,
        cfg.indexing.maxBatchSize,
        cfg.indexing.maxTimeWindow
      )(CacheProjectionId("BlazegraphGlobalEventLog"), rcr)
  }

  make[ProgressesCache].named("blazegraph-progresses").from { (cfg: BlazegraphViewsConfig, as: ActorSystem[Nothing]) =>
    KeyValueStore.distributed[ProjectionId, ProjectionProgress[Unit]](
      "blazegraph-views-progresses",
      (_, v) => v.timestamp.toEpochMilli
    )(as, cfg.keyValueStore)
  }

  make[BlazegraphIndexingCoordinator].fromEffect {
    (
        views: BlazegraphViews,
        indexingLog: BlazegraphIndexingEventLog,
        client: BlazegraphClient,
        projection: Projection[Unit],
        cache: ProgressesCache @Id("blazegraph-progresses"),
        config: BlazegraphViewsConfig,
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        base: BaseUri,
        uuidF: UUIDF
    ) =>
      BlazegraphIndexingCoordinator(views, indexingLog, client, projection, cache, config)(
        uuidF,
        as,
        scheduler,
        base,
        cr
      )
  }

  make[BlazegraphViews]
    .fromEffect {
      (
          cfg: BlazegraphViewsConfig,
          log: EventLog[Envelope[BlazegraphViewEvent]],
          contextResolution: ResolverContextResolution,
          permissions: Permissions,
          orgs: Organizations,
          projects: Projects,
          clock: Clock[UIO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          scheduler: Scheduler
      ) =>
        BlazegraphViews(cfg, log, contextResolution, permissions, orgs, projects)(
          uuidF,
          clock,
          scheduler,
          as
        )
    }

  make[BlazegraphViewsQuery].from {
    (
        acls: Acls,
        views: BlazegraphViews,
        projects: Projects,
        client: BlazegraphClient,
        cfg: BlazegraphViewsConfig
    ) =>
      BlazegraphViewsQuery(acls, views, projects, client)(cfg.indexing)
  }

  make[ProgressesStatistics].named("blazegraph-statistics").from {
    (cache: ProgressesCache @Id("blazegraph-progresses"), projectsCounts: ProjectsCounts) =>
      new ProgressesStatistics(cache, projectsCounts)
  }

  make[BlazegraphViewsRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        projects: Projects,
        views: BlazegraphViews,
        viewsQuery: BlazegraphViewsQuery,
        progresses: ProgressesStatistics @Id("blazegraph-statistics"),
        coordinator: BlazegraphIndexingCoordinator,
        baseUri: BaseUri,
        cfg: BlazegraphViewsConfig,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new BlazegraphViewsRoutes(views, viewsQuery, identities, acls, projects, progresses, coordinator.restart)(
        baseUri,
        s,
        cr,
        ordering,
        cfg.pagination
      )
  }
  make[BlazegraphViewsMigration].from { (views: BlazegraphViews) =>
    new BlazegraphViewsMigrationImpl(views)
  }
  make[BlazegraphScopeInitialization]
  many[ScopeInitialization].ref[BlazegraphScopeInitialization]

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/blazegraph-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      blazegraphCtx     <- ContextValue.fromFile("contexts/blazegraph.json")
      blazegraphMetaCtx <- ContextValue.fromFile("contexts/blazegraph-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.blazegraph         -> blazegraphCtx,
      contexts.blazegraphMetadata -> blazegraphMetaCtx
    )
  )

  many[ResourceToSchemaMappings].add(
    ResourceToSchemaMappings(Label.unsafe("views") -> viewsSchemaId.iri)
  )

  many[ApiMappings].add(BlazegraphViews.mappings)

  many[PriorityRoute].add { (route: BlazegraphViewsRoutes) => PriorityRoute(priority, route.routes) }

  many[ServiceDependency].add { new BlazegraphServiceDependency(_) }

  make[BlazegraphViewReferenceExchange]
  many[ReferenceExchange].ref[BlazegraphViewReferenceExchange]

  make[BlazegraphViewEventExchange]
  many[EventExchange].named("view").ref[BlazegraphViewEventExchange]
  many[EventExchange].ref[BlazegraphViewEventExchange]

}
