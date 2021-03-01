package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCoordinator.BlazegraphIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.{BlazegraphGlobalEventLog, BlazegraphIndexingCoordinator}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, BlazegraphViewsConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.{EventExchange, EventExchangeCollection, GlobalEventLog}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Event, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Message, Projection, ProjectionId, ProjectionProgress}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Blazegraph plugin wiring
  */
object BlazegraphPluginModule extends ModuleDef {
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

  make[GlobalEventLog[Message[ResourceF[Graph]]]].from {
    (
        cfg: BlazegraphViewsConfig,
        eventLog: EventLog[Envelope[Event]],
        projects: Projects,
        orgs: Organizations,
        eventExchanges: EventExchangeCollection
    ) =>
      implicit val projectionId: ProjectionId = CacheProjectionId("BlazegraphGlobalEventLog")
      BlazegraphGlobalEventLog(
        eventLog,
        projects,
        orgs,
        eventExchanges,
        cfg.indexing.maxBatchSize,
        cfg.indexing.maxTimeWindow
      )
  }

  make[ProgressesCache].named("blazegraph-progresses").from { (cfg: BlazegraphViewsConfig, as: ActorSystem[Nothing]) =>
    KeyValueStore.distributed[ProjectionId, ProjectionProgress[Unit]](
      "blazegraph-views-progresses",
      (_, v) => v.timestamp.toEpochMilli
    )(as, cfg.keyValueStore)
  }

  make[BlazegraphIndexingCoordinator].fromEffect {
    (
        eventLog: GlobalEventLog[Message[ResourceF[Graph]]],
        client: BlazegraphClient,
        projection: Projection[Unit],
        cache: ProgressesCache @Id("blazegraph-progresses"),
        config: BlazegraphViewsConfig,
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        cr: RemoteContextResolution,
        base: BaseUri
    ) =>
      BlazegraphIndexingCoordinator(eventLog, client, projection, cache, config)(as, scheduler, base, cr)
  }

  make[BlazegraphViews]
    .fromEffect {
      (
          cfg: BlazegraphViewsConfig,
          log: EventLog[Envelope[BlazegraphViewEvent]],
          permissions: Permissions,
          orgs: Organizations,
          projects: Projects,
          coordinator: BlazegraphIndexingCoordinator,
          clock: Clock[UIO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          scheduler: Scheduler,
          cr: RemoteContextResolution
      ) =>
        BlazegraphViews(cfg, log, permissions, orgs, projects, coordinator)(uuidF, clock, scheduler, as, cr)
    }

  many[EventExchange].add { (views: BlazegraphViews) => views.eventExchange }

  make[BlazegraphViewsQuery].from {
    (
        acls: Acls,
        views: BlazegraphViews,
        client: BlazegraphClient,
        cfg: BlazegraphViewsConfig
    ) =>
      BlazegraphViewsQuery(acls, views, client)(cfg.indexing)
  }

  make[ProgressesStatistics].named("blazegraph-statistics").from {
    (cache: ProgressesCache @Id("blazegraph-progresses"), projectsCounts: ProjectsCounts) =>
      new ProgressesStatistics(cache, projectsCounts)
  }

  many[ServiceDependency].add { new BlazegraphServiceDependency(_) }

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
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      new BlazegraphViewsRoutes(views, viewsQuery, identities, acls, projects, progresses, coordinator)(
        baseUri,
        s,
        cfg.indexing,
        cr,
        ordering
      )
  }

  make[BlazegraphPlugin]

  make[BlazegraphScopeInitialization]
  many[ScopeInitialization].ref[BlazegraphScopeInitialization]

  make[BlazegraphViewReferenceExchange]
  many[ReferenceExchange].ref[BlazegraphViewReferenceExchange]
}
