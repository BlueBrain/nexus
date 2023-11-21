package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.typed.ActorSystem
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.deletion.{ElasticSearchDeletionTask, EventMetricsDeletionTask}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, schema => viewsSchemaId, ElasticSearchFiles, ElasticSearchView, ElasticSearchViewEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.{DefaultViewsQuery, ElasticSearchQueryError}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
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
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{PipeChain, ReferenceRegistry, Supervisor}
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * ElasticSearch plugin wiring.
  */
class ElasticSearchPluginModule(priority: Int) extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[ElasticSearchViewsConfig].from { ElasticSearchViewsConfig.load(_) }

  make[ElasticSearchFiles].fromEffect { ElasticSearchFiles.mk(loader) }

  make[HttpClient].named("elasticsearch-client").from {
    val httpConfig = HttpClientConfig.noRetry(true)
    (as: ActorSystem[Nothing]) => HttpClient()(httpConfig, as.classicSystem)
  }

  make[ElasticSearchClient].from {
    (
        cfg: ElasticSearchViewsConfig,
        client: HttpClient @Id("elasticsearch-client"),
        as: ActorSystem[Nothing],
        files: ElasticSearchFiles
    ) =>
      new ElasticSearchClient(client, cfg.base, cfg.maxIndexPathLength, files.emptyResults)(
        cfg.credentials,
        as.classicSystem
      )
  }

  make[ValidateElasticSearchView].from {
    (
        registry: ReferenceRegistry,
        permissions: Permissions,
        client: ElasticSearchClient,
        config: ElasticSearchViewsConfig,
        files: ElasticSearchFiles,
        xas: Transactors
    ) =>
      ValidateElasticSearchView(
        PipeChain.validate(_, registry),
        permissions,
        client: ElasticSearchClient,
        config.prefix,
        config.maxViewRefs,
        xas,
        files.defaultMapping,
        files.defaultSettings
      )
  }

  make[ElasticSearchViews].fromEffect {
    (
        fetchContext: FetchContext[ContextRejection],
        contextResolution: ResolverContextResolution,
        validateElasticSearchView: ValidateElasticSearchView,
        config: ElasticSearchViewsConfig,
        files: ElasticSearchFiles,
        xas: Transactors,
        api: JsonLdApi,
        clock: Clock[IO],
        uuidF: UUIDF
    ) =>
      ElasticSearchViews(
        fetchContext.mapRejection(ProjectContextRejection),
        contextResolution,
        validateElasticSearchView,
        config.eventLog,
        config.prefix,
        xas,
        files.defaultMapping,
        files.defaultSettings,
        clock
      )(api, uuidF)
  }

  make[ElasticSearchCoordinator].fromEffect {
    (
        views: ElasticSearchViews,
        graphStream: GraphResourceStream,
        registry: ReferenceRegistry,
        supervisor: Supervisor,
        client: ElasticSearchClient,
        config: ElasticSearchViewsConfig,
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      ElasticSearchCoordinator(
        views,
        graphStream,
        registry,
        supervisor,
        client,
        config
      )(cr)
  }

  make[EventMetricsProjection].fromEffect {
    (
        metricEncoders: Set[ScopedEventMetricEncoder[_]],
        xas: Transactors,
        supervisor: Supervisor,
        client: ElasticSearchClient,
        config: ElasticSearchViewsConfig,
        files: ElasticSearchFiles
    ) =>
      EventMetricsProjection(
        metricEncoders,
        supervisor,
        client,
        xas,
        config.batch,
        config.metricsQuery,
        config.prefix,
        files.metricsMapping,
        files.metricsSettings
      )
  }

  make[ElasticSearchViewsQuery].from {
    (
        aclCheck: AclCheck,
        views: ElasticSearchViews,
        client: ElasticSearchClient,
        xas: Transactors,
        cfg: ElasticSearchViewsConfig
    ) =>
      ElasticSearchViewsQuery(
        aclCheck,
        views,
        client,
        cfg.prefix,
        xas
      )
  }

  make[DefaultViewsQuery.Elasticsearch].from {
    (
        aclCheck: AclCheck,
        client: ElasticSearchClient,
        xas: Transactors,
        baseUri: BaseUri,
        config: ElasticSearchViewsConfig
    ) => DefaultViewsQuery(aclCheck, client, config, config.prefix, xas)(baseUri)
  }

  make[ElasticSearchViewsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: ElasticSearchViews,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: AggregateIndexingAction,
        viewsQuery: ElasticSearchViewsQuery,
        shift: ElasticSearchView.Shift,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new ElasticSearchViewsRoutes(
        identities,
        aclCheck,
        views,
        viewsQuery,
        schemeDirectives,
        indexingAction(_, _, _)(shift)
      )(
        baseUri,
        cr,
        ordering,
        fusionConfig
      )
  }

  make[ElasticSearchQueryRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        schemeDirectives: DeltaSchemeDirectives,
        defaultViewsQuery: DefaultViewsQuery.Elasticsearch,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        resourcesToSchemaSet: Set[ResourceToSchemaMappings],
        esConfig: ElasticSearchViewsConfig,
        fetchContext: FetchContext[ContextRejection]
    ) =>
      val resourceToSchema = resourcesToSchemaSet.foldLeft(ResourceToSchemaMappings.empty)(_ + _)
      new ElasticSearchQueryRoutes(
        identities,
        aclCheck,
        resourceToSchema,
        schemeDirectives,
        defaultViewsQuery
      )(
        baseUri,
        esConfig.pagination,
        cr,
        ordering,
        fetchContext.mapRejection(ElasticSearchQueryError.ProjectContextRejection)
      )
  }

  make[ElasticSearchIndexingRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: ElasticSearchViews,
        projections: Projections,
        projectionErrors: ProjectionErrors,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        esConfig: ElasticSearchViewsConfig,
        ordering: JsonKeyOrdering,
        viewsQuery: ElasticSearchViewsQuery
    ) =>
      new ElasticSearchIndexingRoutes(
        identities,
        aclCheck,
        views.fetchIndexingView(_, _),
        projections,
        projectionErrors,
        schemeDirectives,
        viewsQuery
      )(
        baseUri,
        esConfig.pagination,
        cr,
        ordering
      )
  }

  make[IdResolution].from { (defaultViewsQuery: DefaultViewsQuery.Elasticsearch, shifts: ResourceShifts) =>
    new IdResolution(defaultViewsQuery, (resourceRef, projectRef) => shifts.fetch(resourceRef, projectRef))
  }

  make[IdResolutionRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        idResolution: IdResolution,
        ordering: JsonKeyOrdering,
        rcr: RemoteContextResolution @Id("aggregate"),
        fusionConfig: FusionConfig,
        baseUri: BaseUri
    ) =>
      new IdResolutionRoutes(identities, aclCheck, idResolution)(
        baseUri,
        ordering,
        rcr,
        fusionConfig
      )
  }

  make[ElasticSearchScopeInitialization]
    .from { (views: ElasticSearchViews, serviceAccount: ServiceAccount, config: ElasticSearchViewsConfig) =>
      new ElasticSearchScopeInitialization(views, serviceAccount, config.defaults)
    }

  many[ScopeInitialization].ref[ElasticSearchScopeInitialization]

  many[ProjectDeletionTask].add { (views: ElasticSearchViews) => ElasticSearchDeletionTask(views) }

  many[ProjectDeletionTask].add { (client: ElasticSearchClient, config: ElasticSearchViewsConfig) =>
    new EventMetricsDeletionTask(client, config.prefix)
  }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/elasticsearch-metadata.json"))

  make[MetadataContextValue]
    .named("search-metadata")
    .from((agg: Set[MetadataContextValue]) => agg.foldLeft(MetadataContextValue.empty)(_ merge _))

  make[MetadataContextValue]
    .named("indexing-metadata")
    .from { (listingsMetadataCtx: MetadataContextValue @Id("search-metadata")) =>
      MetadataContextValue(listingsMetadataCtx.value.visit(obj = { case ContextObject(obj) =>
        ContextObject(obj.filterKeys(_.startsWith("_")))
      }))
    }

  many[SseEncoder[_]].add { base: BaseUri => ElasticSearchViewEvent.sseEncoder(base) }

  many[ScopedEventMetricEncoder[_]].add { ElasticSearchViewEvent.esViewMetricEncoder }

  many[RemoteContextResolution].addEffect {
    (
        searchMetadataCtx: MetadataContextValue @Id("search-metadata"),
        indexingMetadataCtx: MetadataContextValue @Id("indexing-metadata")
    ) =>
      for {
        aggregationsCtx      <- ContextValue.fromFile("contexts/aggregations.json")
        elasticsearchCtx     <- ContextValue.fromFile("contexts/elasticsearch.json")
        elasticsearchMetaCtx <- ContextValue.fromFile("contexts/elasticsearch-metadata.json")
        elasticsearchIdxCtx  <- ContextValue.fromFile("contexts/elasticsearch-indexing.json")
        offsetCtx            <- ContextValue.fromFile("contexts/offset.json")
        statisticsCtx        <- ContextValue.fromFile("contexts/statistics.json")
      } yield RemoteContextResolution.fixed(
        contexts.aggregations          -> aggregationsCtx,
        contexts.elasticsearch         -> elasticsearchCtx,
        contexts.elasticsearchMetadata -> elasticsearchMetaCtx,
        contexts.elasticsearchIndexing -> elasticsearchIdxCtx,
        contexts.indexingMetadata      -> indexingMetadataCtx.value,
        contexts.searchMetadata        -> searchMetadataCtx.value,
        Vocabulary.contexts.offset     -> offsetCtx,
        Vocabulary.contexts.statistics -> statisticsCtx
      )
  }

  many[ResourceToSchemaMappings].add(
    ResourceToSchemaMappings(Label.unsafe("views") -> viewsSchemaId.iri)
  )

  many[ApiMappings].add(ElasticSearchViews.mappings)

  many[PriorityRoute].add {
    (
        es: ElasticSearchViewsRoutes,
        query: ElasticSearchQueryRoutes,
        indexing: ElasticSearchIndexingRoutes,
        idResolutionRoute: IdResolutionRoutes,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri
    ) =>
      PriorityRoute(
        priority,
        ElasticSearchViewsRoutesHandler(
          schemeDirectives,
          es.routes,
          query.routes,
          indexing.routes,
          idResolutionRoute.routes
        )(baseUri),
        requiresStrictEntity = true
      )
  }

  many[ServiceDependency].add { new ElasticSearchServiceDependency(_) }

  many[IndexingAction].add {
    (
        views: ElasticSearchViews,
        registry: ReferenceRegistry,
        client: ElasticSearchClient,
        config: ElasticSearchViewsConfig,
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      ElasticSearchIndexingAction(views, registry, client, config.syncIndexingTimeout, config.syncIndexingRefresh)(
        cr
      )
  }

  make[ElasticSearchView.Shift].from { (views: ElasticSearchViews, base: BaseUri, files: ElasticSearchFiles) =>
    ElasticSearchView.shift(views, files.defaultMapping, files.defaultSettings)(base)
  }

  many[ResourceShift[_, _, _]].ref[ElasticSearchView.Shift]

}
