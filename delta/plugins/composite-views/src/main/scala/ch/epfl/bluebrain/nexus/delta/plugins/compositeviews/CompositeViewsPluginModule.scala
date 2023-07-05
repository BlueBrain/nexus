package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.deletion.CompositeViewsDeletionTask
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.{CompositeSpaces, CompositeViewsCoordinator, MetadataPredicates}
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
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{PipeChain, ReferenceRegistry, Supervisor}
import com.typesafe.config.Config
import distage.ModuleDef
import izumi.distage.model.definition.Id
import monix.bio.UIO
import monix.execution.Scheduler

class CompositeViewsPluginModule(priority: Int, appConfig: Config) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  private val config: CompositeViewsConfig = CompositeViewsConfig.load(appConfig)
  make[CompositeViewsConfig].from { config }

  make[DeltaClient].from { (as: ActorSystem[Nothing], sc: Scheduler) =>
    val httpClient = HttpClient()(config.remoteSourceClient.http, as.classicSystem, sc)
    DeltaClient(httpClient, config.remoteSourceClient.retryDelay)(as, sc)
  }

  make[ValidateCompositeView].from {
    (
        aclCheck: AclCheck,
        projects: Projects,
        permissions: Permissions,
        client: ElasticSearchClient,
        deltaClient: DeltaClient,
        crypto: Crypto,
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
        blazeClient: BlazegraphClient @Id("blazegraph-indexing-client"),
        baseUri: BaseUri
    ) =>
      CompositeSpaces.Builder(config.prefix, esClient, config.elasticsearchBatch, blazeClient, config.blazegraphBatch)(
        baseUri
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

  make[RemoteGraphStream].from { (deltaClient: DeltaClient, metadataPredicates: MetadataPredicates) =>
    new RemoteGraphStream(deltaClient, config.remoteSourceClient, metadataPredicates)
  }

  make[CompositeGraphStream].from { (local: GraphResourceStream, remote: RemoteGraphStream) =>
    CompositeGraphStream(local, remote)
  }

  make[CompositeViewsCoordinator].fromEffect {
    (
        compositeViews: CompositeViews,
        supervisor: Supervisor,
        registry: ReferenceRegistry,
        graphStream: CompositeGraphStream,
        buildSpaces: CompositeSpaces.Builder,
        compositeProjections: CompositeProjections,
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      CompositeViewsCoordinator(
        compositeViews,
        supervisor,
        PipeChain.compile(_, registry),
        graphStream,
        buildSpaces.apply,
        compositeProjections
      )(cr)
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
        client: BlazegraphClient @Id("blazegraph-query-client")
    ) => BlazegraphQuery(aclCheck, views, client, config.prefix)
  }

  make[ElasticSearchQuery].from { (aclCheck: AclCheck, views: CompositeViews, client: ElasticSearchClient) =>
    ElasticSearchQuery(aclCheck, views, client, config.prefix)
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
