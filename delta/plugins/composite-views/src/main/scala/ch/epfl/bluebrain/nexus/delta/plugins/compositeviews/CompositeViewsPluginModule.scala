package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.typed.ActorSystem
import cats.effect.{Clock, ContextShift, IO, Timer}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.DefaultProperties
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.deletion.CompositeViewsDeletionTask
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration.MigrateCompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.{CompositeIndexingDetails, CompositeProjections}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes.{CompositeViewsIndexingRoutes, CompositeViewsRoutes, CompositeViewsRoutesHandler}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeRestartStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeGraphStream, RemoteGraphStream}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.rdf.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.auth.AuthTokenProvider
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, Projects}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionErrors
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{PipeChain, ReferenceRegistry, Supervisor}
import distage.ModuleDef
import izumi.distage.model.definition.Id
import monix.execution.Scheduler

class CompositeViewsPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[CompositeViewsConfig].fromEffect { cfg => CompositeViewsConfig.load(cfg) }

  make[DeltaClient].from {
    (
        cfg: CompositeViewsConfig,
        as: ActorSystem[Nothing],
        sc: Scheduler,
        c: ContextShift[IO],
        authTokenProvider: AuthTokenProvider
    ) =>
      val httpClient = HttpClient()(cfg.remoteSourceClient.http, as.classicSystem, sc)
      DeltaClient(httpClient, authTokenProvider, cfg.remoteSourceCredentials, cfg.remoteSourceClient.retryDelay)(
        as,
        sc,
        c
      )
  }

  make[BlazegraphClient].named("blazegraph-composite-indexing-client").from {
    (
        cfg: CompositeViewsConfig,
        client: HttpClient @Id("http-indexing-client"),
        as: ActorSystem[Nothing],
        properties: DefaultProperties
    ) =>
      BlazegraphClient(
        client,
        cfg.blazegraphAccess.base,
        cfg.blazegraphAccess.credentials,
        cfg.blazegraphAccess.queryTimeout,
        properties.value
      )(as.classicSystem)
  }

  make[BlazegraphClient].named("blazegraph-composite-query-client").from {
    (
        cfg: CompositeViewsConfig,
        client: HttpClient @Id("http-query-client"),
        as: ActorSystem[Nothing],
        properties: DefaultProperties
    ) =>
      BlazegraphClient(
        client,
        cfg.blazegraphAccess.base,
        cfg.blazegraphAccess.credentials,
        cfg.blazegraphAccess.queryTimeout,
        properties.value
      )(as.classicSystem)
  }

  make[ValidateCompositeView].from {
    (
        aclCheck: AclCheck,
        projects: Projects,
        permissions: Permissions,
        client: ElasticSearchClient,
        deltaClient: DeltaClient,
        config: CompositeViewsConfig,
        baseUri: BaseUri
    ) =>
      ValidateCompositeView(
        aclCheck,
        projects,
        permissions.fetchPermissionSet.toUIO,
        client,
        deltaClient,
        config.prefix,
        config.sources.maxSources,
        config.maxProjections
      )(baseUri)
  }

  make[CompositeViews].fromEffect {
    (
        fetchContext: FetchContext[ContextRejection],
        contextResolution: ResolverContextResolution,
        validate: ValidateCompositeView,
        config: CompositeViewsConfig,
        xas: Transactors,
        api: JsonLdApi,
        uuidF: UUIDF,
        clock: Clock[IO],
        timer: Timer[IO]
    ) =>
      CompositeViews(
        fetchContext.mapRejection(ProjectContextRejection),
        contextResolution,
        validate,
        config,
        xas
      )(
        api,
        clock,
        timer,
        uuidF
      )
  }

  make[CompositeProjections].fromEffect {
    (
        supervisor: Supervisor,
        xas: Transactors,
        config: CompositeViewsConfig,
        projectionConfig: ProjectionConfig,
        clock: Clock[IO],
        timer: Timer[IO],
        cs: ContextShift[IO]
    ) =>
      val compositeRestartStore = new CompositeRestartStore(xas)
      val compositeProjections  =
        CompositeProjections(
          compositeRestartStore,
          xas,
          projectionConfig.query,
          projectionConfig.batch,
          config.restartCheckInterval
        )(clock, timer, cs)

      CompositeRestartStore
        .deleteExpired(compositeRestartStore, supervisor, projectionConfig)(clock, timer)
        .as(compositeProjections)
  }

  make[CompositeSpaces].from {
    (
        esClient: ElasticSearchClient,
        blazeClient: BlazegraphClient @Id("blazegraph-composite-indexing-client"),
        cfg: CompositeViewsConfig
    ) =>
      CompositeSpaces(cfg.prefix, esClient, blazeClient)
  }

  make[CompositeSinks].from {
    (
        esClient: ElasticSearchClient,
        blazeClient: BlazegraphClient @Id("blazegraph-composite-indexing-client"),
        cfg: CompositeViewsConfig,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      CompositeSinks(cfg.prefix, esClient, blazeClient, cfg)(
        baseUri,
        cr
      )
  }

  make[MetadataPredicates].fromEffect {
    (
        listingsMetadataCtx: MetadataContextValue @Id("search-metadata"),
        api: JsonLdApi,
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      JsonLdContext(listingsMetadataCtx.value)(api, cr, JsonLdOptions.defaults)
        .map(_.aliasesInv.keySet.map(Triple.predicate))
        .map(MetadataPredicates)
  }

  make[RemoteGraphStream].from {
    (
        deltaClient: DeltaClient,
        config: CompositeViewsConfig,
        metadataPredicates: MetadataPredicates,
        timer: Timer[IO],
        cs: ContextShift[IO]
    ) =>
      new RemoteGraphStream(deltaClient, config.remoteSourceClient, metadataPredicates)(timer, cs)
  }

  make[CompositeGraphStream].from { (local: GraphResourceStream, remote: RemoteGraphStream) =>
    CompositeGraphStream(local, remote)
  }

  many[CompositeProjectionLifeCycle.Hook].addValue(CompositeProjectionLifeCycle.NoopHook)

  make[CompositeProjectionLifeCycle].from {
    (
        hooks: Set[CompositeProjectionLifeCycle.Hook],
        registry: ReferenceRegistry,
        graphStream: CompositeGraphStream,
        spaces: CompositeSpaces,
        sinks: CompositeSinks,
        compositeProjections: CompositeProjections,
        timer: Timer[IO],
        cs: ContextShift[IO]
    ) =>
      CompositeProjectionLifeCycle(
        hooks,
        PipeChain.compile(_, registry),
        graphStream,
        spaces,
        sinks,
        compositeProjections
      )(timer, cs)
  }

  private def isCompositeMigrationRunning =
    sys.env.getOrElse("MIGRATE_COMPOSITE_VIEWS", "false").toBooleanOption.getOrElse(false)
  make[CompositeViewsCoordinator].fromEffect {
    (
        compositeViews: CompositeViews,
        supervisor: Supervisor,
        lifecycle: CompositeProjectionLifeCycle,
        config: CompositeViewsConfig,
        xas: Transactors
    ) =>
      IO.whenA(isCompositeMigrationRunning)(new MigrateCompositeViews(xas).run.void) >>
        CompositeViewsCoordinator(
          compositeViews,
          supervisor,
          lifecycle,
          config
        )
  }

  many[ProjectDeletionTask].add { (views: CompositeViews) => CompositeViewsDeletionTask(views) }

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
    (
        aclCheck: AclCheck,
        views: CompositeViews,
        client: BlazegraphClient @Id("blazegraph-composite-query-client"),
        cfg: CompositeViewsConfig
    ) => BlazegraphQuery(aclCheck, views, client, cfg.prefix)
  }

  make[ElasticSearchQuery].from {
    (aclCheck: AclCheck, views: CompositeViews, client: ElasticSearchClient, cfg: CompositeViewsConfig) =>
      ElasticSearchQuery(aclCheck, views, client, cfg.prefix)
  }

  make[CompositeViewsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: CompositeViews,
        blazegraphQuery: BlazegraphQuery,
        elasticSearchQuery: ElasticSearchQuery,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new CompositeViewsRoutes(
        identities,
        aclCheck,
        views,
        blazegraphQuery,
        elasticSearchQuery,
        schemeDirectives
      )(baseUri, cr, ordering, fusionConfig)
  }

  make[CompositeViewsIndexingRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: CompositeViews,
        graphStream: CompositeGraphStream,
        projections: CompositeProjections,
        projectionErrors: ProjectionErrors,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        config: CompositeViewsConfig,
        c: ContextShift[IO],
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new CompositeViewsIndexingRoutes(
        identities,
        aclCheck,
        views.fetchIndexingView,
        views.expand,
        CompositeIndexingDetails(projections, graphStream, config.prefix),
        projections,
        projectionErrors,
        schemeDirectives
      )(baseUri, config.pagination, c, cr, ordering)
  }

  make[CompositeView.Shift].from { (views: CompositeViews, base: BaseUri) =>
    CompositeView.shift(views)(base)
  }

  many[ResourceShift[_, _, _]].ref[CompositeView.Shift]

  many[SseEncoder[_]].add { (base: BaseUri) => CompositeViewEvent.sseEncoder(base) }

  many[ScopedEventMetricEncoder[_]].add { () => CompositeViewEvent.compositeViewMetricEncoder }

  many[PriorityRoute].add {
    (
        cv: CompositeViewsRoutes,
        indexing: CompositeViewsIndexingRoutes,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri
    ) =>
      PriorityRoute(
        priority,
        CompositeViewsRoutesHandler(
          schemeDirectives,
          cv.routes,
          indexing.routes
        )(baseUri),
        requiresStrictEntity = true
      )
  }
}
