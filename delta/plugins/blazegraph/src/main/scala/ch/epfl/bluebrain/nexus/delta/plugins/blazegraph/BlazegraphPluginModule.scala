package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCoordinator.{BlazegraphIndexingController, BlazegraphIndexingCoordinator}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.{BlazegraphIndexingCleanup, BlazegraphIndexingCoordinator, BlazegraphIndexingStream, BlazegraphOnEventInstant}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
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
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStreamController, OnEventInstant}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
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

  make[IndexingSource].named("blazegraph-source").from {
    (
        cfg: BlazegraphViewsConfig,
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

  make[ProgressesCache].named("blazegraph-progresses").from { (cfg: BlazegraphViewsConfig, as: ActorSystem[Nothing]) =>
    KeyValueStore.distributed[ProjectionId, ProjectionProgress[Unit]](
      "blazegraph-views-progresses",
      (_, v) => v.timestamp.toEpochMilli
    )(as, cfg.keyValueStore)
  }

  make[BlazegraphIndexingStream].from {
    (
        client: BlazegraphClient,
        projection: Projection[Unit],
        indexingSource: IndexingSource @Id("blazegraph-source"),
        cache: ProgressesCache @Id("blazegraph-progresses"),
        config: BlazegraphViewsConfig,
        scheduler: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        base: BaseUri
    ) =>
      new BlazegraphIndexingStream(client, indexingSource, cache, config, projection)(cr, base, scheduler)
  }

  make[BlazegraphIndexingController].from { (as: ActorSystem[Nothing]) =>
    new IndexingStreamController[IndexingBlazegraphView](BlazegraphViews.moduleType)(as)
  }

  make[BlazegraphIndexingCleanup].from {
    (client: BlazegraphClient, cache: ProgressesCache @Id("blazegraph-progresses")) =>
      new BlazegraphIndexingCleanup(client, cache)
  }

  make[BlazegraphIndexingCoordinator].fromEffect {
    (
        views: BlazegraphViews,
        indexingStream: BlazegraphIndexingStream,
        indexingCleanup: BlazegraphIndexingCleanup,
        indexingController: BlazegraphIndexingController,
        config: BlazegraphViewsConfig,
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        uuidF: UUIDF
    ) =>
      BlazegraphIndexingCoordinator(views, indexingController, indexingStream, indexingCleanup, config)(
        uuidF,
        as,
        scheduler
      )
  }

  make[BlazegraphViews]
    .fromEffect {
      (
          cfg: BlazegraphViewsConfig,
          log: EventLog[Envelope[BlazegraphViewEvent]],
          contextResolution: ResolverContextResolution,
          resourceIdCheck: ResourceIdCheck,
          client: BlazegraphClient,
          permissions: Permissions,
          orgs: Organizations,
          projects: Projects,
          clock: Clock[UIO],
          uuidF: UUIDF,
          as: ActorSystem[Nothing],
          scheduler: Scheduler
      ) =>
        BlazegraphViews(cfg, log, contextResolution, permissions, orgs, projects, resourceIdCheck, client)(
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
        indexingController: BlazegraphIndexingController,
        baseUri: BaseUri,
        cfg: BlazegraphViewsConfig,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new BlazegraphViewsRoutes(views, viewsQuery, identities, acls, projects, progresses, indexingController.restart)(
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

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/sparql-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      blazegraphCtx     <- ContextValue.fromFile("contexts/sparql.json")
      blazegraphMetaCtx <- ContextValue.fromFile("contexts/sparql-metadata.json")
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

  many[ReferenceExchange].add { (views: BlazegraphViews) =>
    BlazegraphViews.referenceExchange(views)
  }

  make[BlazegraphViewEventExchange]
  many[EventExchange].named("view").ref[BlazegraphViewEventExchange]
  many[EventExchange].ref[BlazegraphViewEventExchange]
  many[EntityType].add(EntityType(BlazegraphViews.moduleType))
  make[BlazegraphOnEventInstant]
  many[OnEventInstant].ref[BlazegraphOnEventInstant]

}
