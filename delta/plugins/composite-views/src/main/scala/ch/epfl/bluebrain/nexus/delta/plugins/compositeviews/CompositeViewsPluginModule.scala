package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.deletion.CompositeViewsDeletionTask
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.{CompositeProjectionLifeCycle, CompositeSpaces, CompositeViewsCoordinator, MetadataPredicates}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.{CompositeIndexingDetails, CompositeProjections}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes.CompositeViewsRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeRestartStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeGraphStream, RemoteGraphStream}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.rdf.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
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
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{PipeChain, ReferenceRegistry, Supervisor}
import distage.ModuleDef
import izumi.distage.model.definition.Id
import monix.bio.UIO
import monix.execution.Scheduler

class CompositeViewsPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[CompositeViewsConfig].fromEffect { cfg => CompositeViewsConfig.load(cfg) }

  make[DeltaClient].from { (cfg: CompositeViewsConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
    val httpClient = HttpClient()(cfg.remoteSourceClient.http, as.classicSystem, sc)
    DeltaClient(httpClient, cfg.remoteSourceClient.retryDelay)(as, sc)
  }

  make[BlazegraphClient].named("blazegraph-composite-indexing-client").from {
    (
        cfg: CompositeViewsConfig,
        client: HttpClient @Id("http-indexing-client"),
        as: ActorSystem[Nothing]
    ) =>
      BlazegraphClient(
        client,
        cfg.blazegraphAccess.base,
        cfg.blazegraphAccess.credentials,
        cfg.blazegraphAccess.queryTimeout
      )(as.classicSystem)
  }

  make[BlazegraphClient].named("blazegraph-composite-query-client").from {
    (
        cfg: CompositeViewsConfig,
        client: HttpClient @Id("http-query-client"),
        as: ActorSystem[Nothing]
    ) =>
      BlazegraphClient(
        client,
        cfg.blazegraphAccess.base,
        cfg.blazegraphAccess.credentials,
        cfg.blazegraphAccess.queryTimeout
      )(as.classicSystem)
  }

  make[ValidateCompositeView].from {
    (
        aclCheck: AclCheck,
        projects: Projects,
        permissions: Permissions,
        client: ElasticSearchClient,
        deltaClient: DeltaClient,
        crypto: Crypto,
        config: CompositeViewsConfig,
        baseUri: BaseUri
    ) =>
      ValidateCompositeView(
        aclCheck,
        projects,
        permissions.fetchPermissionSet,
        client,
        deltaClient,
        crypto,
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
        crypto: Crypto,
        config: CompositeViewsConfig,
        xas: Transactors,
        api: JsonLdApi,
        uuidF: UUIDF,
        clock: Clock[UIO]
    ) =>
      CompositeViews(
        fetchContext.mapRejection(ProjectContextRejection),
        contextResolution,
        validate,
        crypto,
        config,
        xas
      )(
        api,
        clock,
        uuidF
      )
  }

  make[CompositeProjections].fromEffect {
    (
        supervisor: Supervisor,
        xas: Transactors,
        config: CompositeViewsConfig,
        projectionConfig: ProjectionConfig,
        clock: Clock[UIO]
    ) =>
      val compositeRestartStore = new CompositeRestartStore(xas)
      val compositeProjections  =
        CompositeProjections(
          compositeRestartStore,
          xas,
          projectionConfig.query,
          projectionConfig.batch,
          config.restartCheckInterval
        )(clock)

      CompositeRestartStore
        .deleteExpired(compositeRestartStore, supervisor, projectionConfig)(clock)
        .as(compositeProjections)
  }

  make[CompositeSpaces.Builder].from {
    (
        esClient: ElasticSearchClient,
        blazeClient: BlazegraphClient @Id("blazegraph-composite-indexing-client"),
        cfg: CompositeViewsConfig,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      CompositeSpaces.Builder(cfg.prefix, esClient, blazeClient, cfg)(
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
    (deltaClient: DeltaClient, config: CompositeViewsConfig, metadataPredicates: MetadataPredicates) =>
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
        buildSpaces: CompositeSpaces.Builder,
        compositeProjections: CompositeProjections
    ) =>
      CompositeProjectionLifeCycle(
        hooks,
        PipeChain.compile(_, registry),
        graphStream,
        buildSpaces.apply,
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
        projections: CompositeProjections,
        graphStream: CompositeGraphStream,
        blazegraphQuery: BlazegraphQuery,
        elasticSearchQuery: ElasticSearchQuery,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new CompositeViewsRoutes(
        identities,
        aclCheck,
        views,
        CompositeIndexingDetails(projections, graphStream),
        projections,
        blazegraphQuery,
        elasticSearchQuery,
        schemeDirectives
      )(baseUri, s, cr, ordering, fusionConfig)
  }

  make[CompositeView.Shift].from { (views: CompositeViews, base: BaseUri, crypto: Crypto) =>
    CompositeView.shift(views)(base, crypto)
  }

  many[ResourceShift[_, _, _]].ref[CompositeView.Shift]

  many[SseEncoder[_]].add { (crypto: Crypto, base: BaseUri) => CompositeViewEvent.sseEncoder(crypto)(base) }

  many[ScopedEventMetricEncoder[_]].add { (crypto: Crypto) => CompositeViewEvent.compositeViewMetricEncoder(crypto) }

  many[PriorityRoute].add { (route: CompositeViewsRoutes) =>
    PriorityRoute(priority, route.routes, requiresStrictEntity = true)
  }
}
