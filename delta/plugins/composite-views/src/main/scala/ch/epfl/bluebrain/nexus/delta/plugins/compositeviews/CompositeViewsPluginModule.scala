package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.ActorSystem
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.deletion.CompositeViewsDeletionTask
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.*
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.*
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.{CompositeIndexingDetails, CompositeProjections}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes.{CompositeSupervisionRoutes, CompositeViewsIndexingRoutes, CompositeViewsRoutes, CompositeViewsRoutesHandler}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeRestartStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeGraphStream, RemoteGraphStream}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.rdf.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdOptions
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.auth.AuthTokenProvider
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.*
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, Projects}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionErrors
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PurgeProjectionCoordinator.PurgeProjection
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{PipeChain, ReferenceRegistry, Supervisor}
import distage.ModuleDef
import izumi.distage.model.definition.Id

class CompositeViewsPluginModule(priority: Int) extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[CompositeViewsConfig].fromEffect { cfg => CompositeViewsConfig.load(cfg) }

  make[DeltaClient].from {
    (
        cfg: CompositeViewsConfig,
        as: ActorSystem,
        authTokenProvider: AuthTokenProvider
    ) =>
      val httpConfig = HttpClientConfig.noRetry(true)
      val httpClient = HttpClient()(httpConfig, as)
      DeltaClient(httpClient, authTokenProvider, cfg.remoteSourceCredentials, cfg.remoteSourceClient.retryDelay)(
        as
      )
  }

  make[SparqlClient].named("sparql-composite-indexing-client").fromResource { (cfg: CompositeViewsConfig) =>
    val access = cfg.blazegraphAccess
    SparqlClient(access.sparqlTarget, access.base, access.queryTimeout, cfg.blazegraphAccess.credentials)
  }

  make[SparqlClient].named("sparql-composite-query-client").fromResource { (cfg: CompositeViewsConfig) =>
    val access = cfg.blazegraphAccess
    SparqlClient(access.sparqlTarget, access.base, access.queryTimeout, cfg.blazegraphAccess.credentials)
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
        permissions.fetchPermissionSet,
        client,
        deltaClient,
        config.prefix,
        config.sources.maxSources,
        config.maxProjections
      )(baseUri)
  }

  make[CompositeViews].fromEffect {
    (
        fetchContext: FetchContext,
        contextResolution: ResolverContextResolution,
        validate: ValidateCompositeView,
        config: CompositeViewsConfig,
        xas: Transactors,
        uuidF: UUIDF,
        clock: Clock[IO]
    ) =>
      CompositeViews(
        fetchContext,
        contextResolution,
        validate,
        config.minIntervalRebuild,
        config.eventLog,
        xas,
        clock
      )(uuidF)
  }

  make[CompositeRestartStore].from { (xas: Transactors) =>
    new CompositeRestartStore(xas)
  }

  make[CompositeProjections].from {
    (
        compositeRestartStore: CompositeRestartStore,
        config: CompositeViewsConfig,
        projectionConfig: ProjectionConfig,
        clock: Clock[IO],
        xas: Transactors
    ) =>
      CompositeProjections(
        compositeRestartStore,
        xas,
        projectionConfig.query,
        projectionConfig.batch,
        config.restartCheckInterval,
        clock
      )
  }

  many[PurgeProjection].add { (compositeRestartStore: CompositeRestartStore, projectionConfig: ProjectionConfig) =>
    CompositeRestartStore.purgeExpiredRestarts(compositeRestartStore, projectionConfig.restartPurge)
  }

  make[CompositeSpaces].from {
    (
        esClient: ElasticSearchClient,
        sparqlClient: SparqlClient @Id("sparql-composite-indexing-client"),
        cfg: CompositeViewsConfig
    ) =>
      CompositeSpaces(cfg.prefix, esClient, sparqlClient)
  }

  make[CompositeSinks].from {
    (
        esClient: ElasticSearchClient,
        sparqlClient: SparqlClient @Id("sparql-composite-indexing-client"),
        cfg: CompositeViewsConfig,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      CompositeSinks(
        cfg.prefix,
        esClient,
        cfg.elasticsearchBatch,
        sparqlClient,
        cfg.blazegraphBatch,
        cfg.sinkConfig,
        cfg.retryStrategy
      )(baseUri, cr)
  }

  make[MetadataPredicates].fromEffect {
    (
        listingsMetadataCtx: MetadataContextValue @Id("search-metadata"),
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      JsonLdContext(listingsMetadataCtx.value)(cr, JsonLdOptions.defaults)
        .map(_.aliasesInv.keySet.map(Triple.predicate))
        .map(MetadataPredicates)
  }

  make[RemoteGraphStream].from {
    (
        deltaClient: DeltaClient,
        config: CompositeViewsConfig,
        metadataPredicates: MetadataPredicates
    ) =>
      new RemoteGraphStream(deltaClient, config.remoteSourceClient, metadataPredicates)
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
        compositeProjections: CompositeProjections
    ) =>
      CompositeProjectionLifeCycle(
        hooks,
        PipeChain.compile(_, registry),
        graphStream,
        spaces,
        sinks,
        compositeProjections
      )
  }

  make[CompositeViewsCoordinator].fromEffect {
    (
        compositeViews: CompositeViews,
        supervisor: Supervisor,
        lifecycle: CompositeProjectionLifeCycle,
        config: CompositeViewsConfig
    ) =>
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
        client: SparqlClient @Id("sparql-composite-query-client"),
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
        elasticSearchQuery
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
        baseUri: BaseUri,
        config: CompositeViewsConfig,
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
        projectionErrors
      )(baseUri, config.pagination, cr, ordering)
  }

  make[CompositeSupervisionRoutes].from {
    (
        views: CompositeViews,
        client: SparqlClient @Id("sparql-composite-indexing-client"),
        identities: Identities,
        aclCheck: AclCheck,
        config: CompositeViewsConfig,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      CompositeSupervisionRoutes(views, client, identities, aclCheck, config.prefix)(cr, ordering)
  }

  many[SseEncoder[?]].add { (base: BaseUri) => CompositeViewEvent.sseEncoder(base) }

  many[PriorityRoute].add {
    (
        cv: CompositeViewsRoutes,
        indexing: CompositeViewsIndexingRoutes,
        supervision: CompositeSupervisionRoutes,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri
    ) =>
      PriorityRoute(
        priority,
        CompositeViewsRoutesHandler(
          schemeDirectives,
          cv.routes,
          indexing.routes,
          supervision.routes
        )(baseUri),
        requiresStrictEntity = true
      )
  }
}
