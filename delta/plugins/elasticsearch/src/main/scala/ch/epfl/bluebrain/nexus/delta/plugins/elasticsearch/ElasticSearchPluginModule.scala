package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.ActorSystem
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.kernel.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.deletion.{ElasticSearchDeletionTask, EventMetricsDeletionTask, MainIndexDeletionTask}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.{ElasticSearchCoordinator, MainIndexingAction, MainIndexingCoordinator}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.main.MainIndexDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.{EventMetricsProjection, EventMetricsQuery, MetricsIndexDef}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, schema => viewsSchemaId, ElasticSearchView, ElasticSearchViewEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.MainIndexQuery
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.views.DefaultIndexDef
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, ProjectScopeResolver, Projects}
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

  make[MetricsIndexDef].fromEffect { (cfg: ElasticSearchViewsConfig) =>
    MetricsIndexDef(cfg.prefix, loader)
  }

  make[DefaultIndexDef].fromEffect { DefaultIndexDef(loader) }

  make[MainIndexDef].fromEffect { (cfg: ElasticSearchViewsConfig) =>
    MainIndexDef(cfg.mainIndex, loader)
  }

  make[HttpClient].named("elasticsearch-client").from {
    val httpConfig = HttpClientConfig.noRetry(true)
    (as: ActorSystem) => HttpClient()(httpConfig, as)
  }

  make[ElasticSearchClient].from {
    (
        cfg: ElasticSearchViewsConfig,
        client: HttpClient @Id("elasticsearch-client"),
        as: ActorSystem
    ) =>
      new ElasticSearchClient(client, cfg.base, cfg.maxIndexPathLength)(cfg.credentials, as)
  }

  make[ValidateElasticSearchView].from {
    (
        registry: ReferenceRegistry,
        permissions: Permissions,
        client: ElasticSearchClient,
        config: ElasticSearchViewsConfig,
        defaultIndex: DefaultIndexDef,
        xas: Transactors
    ) =>
      ValidateElasticSearchView(
        PipeChain.validate(_, registry),
        permissions,
        client: ElasticSearchClient,
        config.prefix,
        config.maxViewRefs,
        xas,
        defaultIndex
      )
  }

  make[ElasticSearchViews].fromEffect {
    (
        fetchContext: FetchContext,
        contextResolution: ResolverContextResolution,
        validateElasticSearchView: ValidateElasticSearchView,
        config: ElasticSearchViewsConfig,
        defaultIndex: DefaultIndexDef,
        xas: Transactors,
        clock: Clock[IO],
        uuidF: UUIDF
    ) =>
      ElasticSearchViews(
        fetchContext,
        contextResolution,
        validateElasticSearchView,
        config.eventLog,
        config.prefix,
        xas,
        defaultIndex,
        clock
      )(uuidF)
  }

  make[MigrateDefaultIndexing].from { (xas: Transactors) => MigrateDefaultIndexing(xas) }

  make[ElasticSearchCoordinator].fromEffect {
    (
        views: ElasticSearchViews,
        graphStream: GraphResourceStream,
        registry: ReferenceRegistry,
        supervisor: Supervisor,
        client: ElasticSearchClient,
        config: ElasticSearchViewsConfig,
        cr: RemoteContextResolution @Id("aggregate"),
        migration: MigrateDefaultIndexing
    ) =>
      migration.run >>
        ElasticSearchCoordinator(
          views,
          graphStream,
          registry,
          supervisor,
          client,
          config
        )(cr)
  }

  make[MainIndexingCoordinator].fromEffect {
    (
        projects: Projects,
        graphStream: GraphResourceStream,
        supervisor: Supervisor,
        client: ElasticSearchClient,
        mainIndex: MainIndexDef,
        config: ElasticSearchViewsConfig,
        cr: RemoteContextResolution @Id("aggregate")
    ) =>
      MainIndexingCoordinator(
        projects,
        graphStream,
        supervisor,
        client,
        mainIndex,
        config.batch,
        config.indexingEnabled
      )(cr)
  }

  make[EventMetricsProjection].fromEffect {
    (
        metricEncoders: Set[ScopedEventMetricEncoder[_]],
        xas: Transactors,
        supervisor: Supervisor,
        projections: Projections,
        client: ElasticSearchClient,
        config: ElasticSearchViewsConfig,
        metricIndex: MetricsIndexDef
    ) =>
      EventMetricsProjection(
        metricEncoders,
        supervisor,
        projections,
        client,
        xas,
        config.batch,
        config.metricsQuery,
        metricIndex,
        config.indexingEnabled
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

  make[MainIndexQuery].from {
    (
        client: ElasticSearchClient,
        baseUri: BaseUri,
        config: ElasticSearchViewsConfig
    ) => MainIndexQuery(client, config.mainIndex)(baseUri)
  }

  make[ElasticSearchViewsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: ElasticSearchViews,
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
        indexingAction(_, _, _)(shift)
      )(
        baseUri,
        cr,
        ordering,
        fusionConfig
      )
  }

  make[MainIndexRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        defaultIndexQuery: MainIndexQuery,
        projections: Projections,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new MainIndexRoutes(identities, aclCheck, defaultIndexQuery, projections)(cr, ordering)
  }

  make[ListingRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        projectScopeResolver: ProjectScopeResolver,
        schemeDirectives: DeltaSchemeDirectives,
        defaultIndexQuery: MainIndexQuery,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        resourcesToSchemaSet: Set[ResourceToSchemaMappings],
        esConfig: ElasticSearchViewsConfig
    ) =>
      val resourceToSchema = resourcesToSchemaSet.foldLeft(ResourceToSchemaMappings.empty)(_ + _)
      new ListingRoutes(
        identities,
        aclCheck,
        projectScopeResolver,
        resourceToSchema,
        schemeDirectives,
        defaultIndexQuery
      )(baseUri, esConfig.pagination, cr, ordering)
  }

  make[ElasticSearchIndexingRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: ElasticSearchViews,
        projections: Projections,
        projectionErrors: ProjectionErrors,
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
        viewsQuery
      )(
        baseUri,
        esConfig.pagination,
        cr,
        ordering
      )
  }

  make[IdResolution].from {
    (projectScopeResolver: ProjectScopeResolver, defaultIndexQuery: MainIndexQuery, shifts: ResourceShifts) =>
      IdResolution(
        projectScopeResolver,
        defaultIndexQuery,
        (resourceRef, projectRef) => shifts.fetch(resourceRef, projectRef)
      )
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

  make[EventMetricsQuery].from { (client: ElasticSearchClient, metricsIndex: MetricsIndexDef) =>
    EventMetricsQuery(client, metricsIndex.name)
  }

  make[ElasticSearchHistoryRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        metricsQuery: EventMetricsQuery,
        rcr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new ElasticSearchHistoryRoutes(identities, aclCheck, metricsQuery)(rcr, ordering)
  }

  many[ProjectDeletionTask].add { (views: ElasticSearchViews) => ElasticSearchDeletionTask(views) }

  many[ProjectDeletionTask].add { (client: ElasticSearchClient, metricsIndex: MetricsIndexDef) =>
    new EventMetricsDeletionTask(client, metricsIndex.name)
  }

  many[ProjectDeletionTask].add { (client: ElasticSearchClient, config: ElasticSearchViewsConfig) =>
    new MainIndexDeletionTask(client, config.mainIndex.index)
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
        query: ListingRoutes,
        defaultIndex: MainIndexRoutes,
        indexing: ElasticSearchIndexingRoutes,
        idResolutionRoute: IdResolutionRoutes,
        historyRoutes: ElasticSearchHistoryRoutes,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri
    ) =>
      PriorityRoute(
        priority,
        ElasticSearchViewsRoutesHandler(
          schemeDirectives,
          es.routes,
          query.routes,
          defaultIndex.routes,
          indexing.routes,
          idResolutionRoute.routes,
          historyRoutes.routes
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

  many[IndexingAction].add {
    (
        client: ElasticSearchClient,
        config: ElasticSearchViewsConfig,
        cr: RemoteContextResolution @Id("aggregate")
    ) => MainIndexingAction(client, config.mainIndex, config.syncIndexingTimeout, config.syncIndexingRefresh)(cr)
  }

  make[ElasticSearchView.Shift].from { (views: ElasticSearchViews, base: BaseUri, defaultIndexDef: DefaultIndexDef) =>
    ElasticSearchView.shift(views, defaultIndexDef)(base)
  }

  many[ResourceShift[_, _, _]].ref[ElasticSearchView.Shift]

}
