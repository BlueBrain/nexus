package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.typed.ActorSystem
import cats.effect.{Clock, ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, schema => viewsSchemaId, BlazegraphView, BlazegraphViewEvent, DefaultProperties}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.{BlazegraphViewsIndexingRoutes, BlazegraphViewsRoutes, BlazegraphViewsRoutesHandler}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.{BlazegraphSlowQueryDeleter, BlazegraphSlowQueryLogger, BlazegraphSlowQueryStore}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ReferenceRegistry, Supervisor}
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Blazegraph plugin wiring
  */
class BlazegraphPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[BlazegraphViewsConfig].from { BlazegraphViewsConfig.load(_) }

  make[DefaultProperties].fromEffect {
    ClasspathResourceUtils.ioPropertiesOf("blazegraph/index.properties").map(DefaultProperties)
  }

  make[HttpClient].named("http-indexing-client").from {
    (
        cfg: BlazegraphViewsConfig,
        as: ActorSystem[Nothing],
        timer: Timer[IO],
        cs: ContextShift[IO]
    ) =>
      HttpClient()(cfg.indexingClient, as.classicSystem, timer, cs)
  }

  make[BlazegraphSlowQueryStore].from { (xas: Transactors) =>
    BlazegraphSlowQueryStore(
      xas
    )
  }

  make[BlazegraphSlowQueryDeleter].fromEffect {
    (supervisor: Supervisor, store: BlazegraphSlowQueryStore, cfg: BlazegraphViewsConfig, timer: Timer[IO]) =>
      BlazegraphSlowQueryDeleter.start(
        supervisor,
        store,
        cfg.slowQueries.logTtl,
        cfg.slowQueries.deleteExpiredLogsEvery
      )(timer)
  }

  make[BlazegraphSlowQueryLogger].from {
    (cfg: BlazegraphViewsConfig, store: BlazegraphSlowQueryStore, clock: Clock[IO]) =>
      BlazegraphSlowQueryLogger(store, cfg.slowQueries.slowQueryThreshold)(clock)
  }

  make[BlazegraphClient].named("blazegraph-indexing-client").from {
    (
        cfg: BlazegraphViewsConfig,
        client: HttpClient @Id("http-indexing-client"),
        as: ActorSystem[Nothing],
        timer: Timer[IO],
        cs: ContextShift[IO],
        properties: DefaultProperties
    ) =>
      BlazegraphClient(client, cfg.base, cfg.credentials, cfg.queryTimeout, properties.value)(
        as.classicSystem,
        timer,
        cs
      )
  }

  make[HttpClient].named("http-query-client").from {
    (
        cfg: BlazegraphViewsConfig,
        as: ActorSystem[Nothing],
        timer: Timer[IO],
        cs: ContextShift[IO]
    ) =>
      HttpClient()(cfg.queryClient, as.classicSystem, timer, cs)
  }

  make[BlazegraphClient].named("blazegraph-query-client").from {
    (
        cfg: BlazegraphViewsConfig,
        client: HttpClient @Id("http-query-client"),
        as: ActorSystem[Nothing],
        timer: Timer[IO],
        cs: ContextShift[IO],
        properties: DefaultProperties
    ) =>
      BlazegraphClient(client, cfg.base, cfg.credentials, cfg.queryTimeout, properties.value)(
        as.classicSystem,
        timer,
        cs
      )
  }

  make[ValidateBlazegraphView].from {
    (
        permissions: Permissions,
        config: BlazegraphViewsConfig,
        xas: Transactors
    ) =>
      ValidateBlazegraphView(
        permissions.fetchPermissionSet,
        config.maxViewRefs,
        xas
      )
  }

  make[BlazegraphViews]
    .fromEffect {
      (
          fetchContext: FetchContext[ContextRejection],
          contextResolution: ResolverContextResolution,
          validate: ValidateBlazegraphView,
          client: BlazegraphClient @Id("blazegraph-indexing-client"),
          config: BlazegraphViewsConfig,
          xas: Transactors,
          api: JsonLdApi,
          clock: Clock[IO],
          contextShift: ContextShift[IO],
          timer: Timer[IO],
          uuidF: UUIDF
      ) =>
        BlazegraphViews(
          fetchContext.mapRejection(ProjectContextRejection),
          contextResolution,
          validate,
          client,
          config.eventLog,
          config.prefix,
          xas
        )(api, clock, contextShift, timer, uuidF)
    }

  make[BlazegraphCoordinator].fromEffect {
    (
        views: BlazegraphViews,
        graphStream: GraphResourceStream,
        registry: ReferenceRegistry,
        supervisor: Supervisor,
        client: BlazegraphClient @Id("blazegraph-indexing-client"),
        config: BlazegraphViewsConfig,
        baseUri: BaseUri,
        timer: Timer[IO],
        cs: ContextShift[IO]
    ) =>
      BlazegraphCoordinator(
        views,
        graphStream,
        registry,
        supervisor,
        client,
        config
      )(baseUri, timer, cs)
  }

  make[BlazegraphViewsQuery].fromEffect {
    (
        aclCheck: AclCheck,
        fetchContext: FetchContext[ContextRejection],
        views: BlazegraphViews,
        client: BlazegraphClient @Id("blazegraph-query-client"),
        slowQueryLogger: BlazegraphSlowQueryLogger,
        cfg: BlazegraphViewsConfig,
        xas: Transactors
    ) =>
      BlazegraphViewsQuery(
        aclCheck,
        fetchContext.mapRejection(ProjectContextRejection),
        views,
        client,
        slowQueryLogger,
        cfg.prefix,
        xas
      )
  }

  make[BlazegraphViewsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: BlazegraphViews,
        viewsQuery: BlazegraphViewsQuery,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: AggregateIndexingAction,
        shift: BlazegraphView.Shift,
        baseUri: BaseUri,
        cfg: BlazegraphViewsConfig,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new BlazegraphViewsRoutes(
        views,
        viewsQuery,
        identities,
        aclCheck,
        schemeDirectives,
        indexingAction(_, _, _)(shift)
      )(
        baseUri,
        cr,
        ordering,
        cfg.pagination,
        fusionConfig
      )
  }

  make[BlazegraphViewsIndexingRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: BlazegraphViews,
        projections: Projections,
        projectionErrors: ProjectionErrors,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        cfg: BlazegraphViewsConfig,
        c: ContextShift[IO],
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new BlazegraphViewsIndexingRoutes(
        views.fetchIndexingView(_, _),
        identities,
        aclCheck,
        projections,
        projectionErrors,
        schemeDirectives
      )(
        baseUri,
        c,
        cr,
        ordering,
        cfg.pagination
      )
  }

  make[BlazegraphScopeInitialization].from {
    (views: BlazegraphViews, serviceAccount: ServiceAccount, config: BlazegraphViewsConfig) =>
      new BlazegraphScopeInitialization(views, serviceAccount, config.defaults)
  }
  many[ScopeInitialization].ref[BlazegraphScopeInitialization]

  many[ProjectDeletionTask].add { (views: BlazegraphViews) => BlazegraphDeletionTask(views) }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/sparql-metadata.json"))

  many[SseEncoder[_]].add { base: BaseUri => BlazegraphViewEvent.sseEncoder(base) }

  many[ScopedEventMetricEncoder[_]].add { BlazegraphViewEvent.bgViewMetricEncoder }

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

  many[PriorityRoute].add {
    (
        bg: BlazegraphViewsRoutes,
        indexing: BlazegraphViewsIndexingRoutes,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri
    ) =>
      PriorityRoute(
        priority,
        BlazegraphViewsRoutesHandler(
          schemeDirectives,
          bg.routes,
          indexing.routes
        )(baseUri),
        requiresStrictEntity = true
      )
  }

  many[ServiceDependency].add { (client: BlazegraphClient @Id("blazegraph-indexing-client")) =>
    new BlazegraphServiceDependency(client)
  }

  many[IndexingAction].add {
    (
        views: BlazegraphViews,
        registry: ReferenceRegistry,
        client: BlazegraphClient @Id("blazegraph-indexing-client"),
        config: BlazegraphViewsConfig,
        baseUri: BaseUri,
        timer: Timer[IO],
        cs: ContextShift[IO]
    ) =>
      BlazegraphIndexingAction(views, registry, client, config.syncIndexingTimeout)(baseUri, timer, cs)
  }

  make[BlazegraphView.Shift].from { (views: BlazegraphViews, base: BaseUri) =>
    BlazegraphView.shift(views)(base)
  }

  many[ResourceShift[_, _, _]].ref[BlazegraphView.Shift]

}
