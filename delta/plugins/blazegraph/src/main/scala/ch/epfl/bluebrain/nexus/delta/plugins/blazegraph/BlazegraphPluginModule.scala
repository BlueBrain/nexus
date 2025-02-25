package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.ActorSystem
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.kernel.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, BlazegraphViewEvent, DefaultProperties}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.{BlazegraphSupervisionRoutes, BlazegraphViewsIndexingRoutes, BlazegraphViewsRoutes, BlazegraphViewsRoutesHandler}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.{BlazegraphSlowQueryDeleter, BlazegraphSlowQueryLogger, BlazegraphSlowQueryStore}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ReferenceRegistry, Supervisor}
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Blazegraph plugin wiring
  */
class BlazegraphPluginModule(priority: Int) extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[BlazegraphViewsConfig].from { BlazegraphViewsConfig.load(_) }

  make[DefaultProperties].fromEffect {
    loader.propertiesOf("blazegraph/index.properties").map(DefaultProperties)
  }

  make[HttpClient].named("http-indexing-client").from { (cfg: BlazegraphViewsConfig, as: ActorSystem) =>
    HttpClient()(cfg.indexingClient, as)
  }

  make[BlazegraphSlowQueryStore].from { (xas: Transactors) => BlazegraphSlowQueryStore(xas) }

  make[BlazegraphSlowQueryDeleter].fromEffect {
    (supervisor: Supervisor, store: BlazegraphSlowQueryStore, cfg: BlazegraphViewsConfig, clock: Clock[IO]) =>
      BlazegraphSlowQueryDeleter.start(
        supervisor,
        store,
        cfg.slowQueries.logTtl,
        cfg.slowQueries.deleteExpiredLogsEvery,
        clock
      )
  }

  make[BlazegraphSlowQueryLogger].from {
    (cfg: BlazegraphViewsConfig, store: BlazegraphSlowQueryStore, clock: Clock[IO]) =>
      BlazegraphSlowQueryLogger(store, cfg.slowQueries.slowQueryThreshold, clock)
  }

  make[BlazegraphClient].named("blazegraph-indexing-client").from {
    (
        cfg: BlazegraphViewsConfig,
        client: HttpClient @Id("http-indexing-client"),
        as: ActorSystem,
        properties: DefaultProperties
    ) =>
      BlazegraphClient(client, cfg.base, cfg.credentials, cfg.queryTimeout, properties.value)(as)
  }

  make[HttpClient].named("http-query-client").from { (as: ActorSystem) =>
    val httpConfig = HttpClientConfig.noRetry(false)
    HttpClient()(httpConfig, as)
  }

  make[BlazegraphClient].named("blazegraph-query-client").from {
    (
        cfg: BlazegraphViewsConfig,
        client: HttpClient @Id("http-query-client"),
        as: ActorSystem,
        properties: DefaultProperties
    ) =>
      BlazegraphClient(client, cfg.base, cfg.credentials, cfg.queryTimeout, properties.value)(as)
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
          fetchContext: FetchContext,
          contextResolution: ResolverContextResolution,
          validate: ValidateBlazegraphView,
          client: BlazegraphClient @Id("blazegraph-indexing-client"),
          config: BlazegraphViewsConfig,
          xas: Transactors,
          clock: Clock[IO],
          uuidF: UUIDF
      ) =>
        BlazegraphViews(
          fetchContext,
          contextResolution,
          validate,
          client,
          config.eventLog,
          config.prefix,
          xas,
          clock
        )(uuidF)
    }

  make[BlazegraphCoordinator].fromEffect {
    (
        views: BlazegraphViews,
        graphStream: GraphResourceStream,
        registry: ReferenceRegistry,
        supervisor: Supervisor,
        client: BlazegraphClient @Id("blazegraph-indexing-client"),
        config: BlazegraphViewsConfig,
        baseUri: BaseUri
    ) =>
      BlazegraphCoordinator(
        views,
        graphStream,
        registry,
        supervisor,
        client,
        config
      )(baseUri)
  }

  make[BlazegraphViewsQuery].fromEffect {
    (
        aclCheck: AclCheck,
        fetchContext: FetchContext,
        views: BlazegraphViews,
        client: BlazegraphClient @Id("blazegraph-query-client"),
        slowQueryLogger: BlazegraphSlowQueryLogger,
        cfg: BlazegraphViewsConfig,
        xas: Transactors
    ) =>
      BlazegraphViewsQuery(
        aclCheck,
        fetchContext,
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
        aclCheck
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
        baseUri: BaseUri,
        cfg: BlazegraphViewsConfig,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new BlazegraphViewsIndexingRoutes(
        views.fetchIndexingView(_, _),
        identities,
        aclCheck,
        projections,
        projectionErrors
      )(
        baseUri,
        cr,
        ordering,
        cfg.pagination
      )
  }

  make[BlazegraphSupervisionRoutes].from {
    (
        views: BlazegraphViews,
        client: BlazegraphClient @Id("blazegraph-indexing-client"),
        identities: Identities,
        aclCheck: AclCheck,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      BlazegraphSupervisionRoutes(views, client, identities, aclCheck)(cr, ordering)
  }

  make[BlazegraphScopeInitialization].from {
    (views: BlazegraphViews, serviceAccount: ServiceAccount, config: BlazegraphViewsConfig) =>
      new BlazegraphScopeInitialization(views, serviceAccount, config.defaults)
  }
  many[ScopeInitialization].ref[BlazegraphScopeInitialization]

  many[ProjectDeletionTask].add { (views: BlazegraphViews) => BlazegraphDeletionTask(views) }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/sparql-metadata.json"))

  many[SseEncoder[_]].add { base: BaseUri => BlazegraphViewEvent.sseEncoder(base) }

  many[RemoteContextResolution].addEffect(
    for {
      blazegraphCtx     <- ContextValue.fromFile("contexts/sparql.json")
      blazegraphMetaCtx <- ContextValue.fromFile("contexts/sparql-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.blazegraph         -> blazegraphCtx,
      contexts.blazegraphMetadata -> blazegraphMetaCtx
    )
  )

  many[ApiMappings].add(BlazegraphViews.mappings)

  many[PriorityRoute].add {
    (
        bg: BlazegraphViewsRoutes,
        indexing: BlazegraphViewsIndexingRoutes,
        supervision: BlazegraphSupervisionRoutes,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri
    ) =>
      PriorityRoute(
        priority,
        BlazegraphViewsRoutesHandler(
          schemeDirectives,
          bg.routes,
          indexing.routes,
          supervision.routes
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
        baseUri: BaseUri
    ) =>
      BlazegraphIndexingAction(views, registry, client, config.syncIndexingTimeout)(baseUri)
  }
}
