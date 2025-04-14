package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.ActorSystem
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, BlazegraphViewEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.query.IncomingOutgoingLinks
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.query.IncomingOutgoingLinks.Queries
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.{BlazegraphSupervisionRoutes, BlazegraphViewsIndexingRoutes, BlazegraphViewsRoutes, BlazegraphViewsRoutesHandler}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.{SparqlSlowQueryDeleter, SparqlSlowQueryLogger, SparqlSlowQueryStore}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.*
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

  make[SparqlSlowQueryStore].from { (xas: Transactors) => SparqlSlowQueryStore(xas) }

  make[SparqlSlowQueryDeleter].fromEffect {
    (supervisor: Supervisor, store: SparqlSlowQueryStore, cfg: BlazegraphViewsConfig, clock: Clock[IO]) =>
      SparqlSlowQueryDeleter.start(
        supervisor,
        store,
        cfg.slowQueries.logTtl,
        cfg.slowQueries.deleteExpiredLogsEvery,
        clock
      )
  }

  make[SparqlSlowQueryLogger].from { (cfg: BlazegraphViewsConfig, store: SparqlSlowQueryStore, clock: Clock[IO]) =>
    SparqlSlowQueryLogger(store, cfg.slowQueries.slowQueryThreshold, clock)
  }

  make[SparqlClient].named("sparql-indexing-client").from { (cfg: BlazegraphViewsConfig, as: ActorSystem) =>
    SparqlClient.indexing(cfg.sparqlTarget, cfg.base, cfg.indexingClient, cfg.queryTimeout)(cfg.credentials, as)
  }

  make[SparqlClient].named("sparql-query-client").from { (cfg: BlazegraphViewsConfig, as: ActorSystem) =>
    SparqlClient.query(cfg.sparqlTarget, cfg.base, cfg.queryTimeout)(cfg.credentials, as)
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
          client: SparqlClient @Id("sparql-indexing-client"),
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
        client: SparqlClient @Id("sparql-indexing-client"),
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

  make[BlazegraphViewsQuery].from {
    (
        aclCheck: AclCheck,
        fetchContext: FetchContext,
        views: BlazegraphViews,
        client: SparqlClient @Id("sparql-query-client"),
        slowQueryLogger: SparqlSlowQueryLogger,
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

  make[IncomingOutgoingLinks].fromEffect {
    (
        fetchContext: FetchContext,
        views: BlazegraphViews,
        client: SparqlClient @Id("sparql-query-client"),
        base: BaseUri
    ) =>
      Queries.load.map { queries =>
        IncomingOutgoingLinks(fetchContext, views, client, queries)(base)
      }
  }

  make[BlazegraphViewsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: BlazegraphViews,
        viewsQuery: BlazegraphViewsQuery,
        incomingOutgoingLinks: IncomingOutgoingLinks,
        baseUri: BaseUri,
        cfg: BlazegraphViewsConfig,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new BlazegraphViewsRoutes(
        views,
        viewsQuery,
        incomingOutgoingLinks,
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
        client: SparqlClient @Id("sparql-indexing-client"),
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

  many[SseEncoder[?]].add { base: BaseUri => BlazegraphViewEvent.sseEncoder(base) }

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

  many[ServiceDependency].add { (client: SparqlClient @Id("sparql-indexing-client")) =>
    new SparqlServiceDependency(client)
  }

  many[IndexingAction].add {
    (
        views: BlazegraphViews,
        registry: ReferenceRegistry,
        client: SparqlClient @Id("sparql-indexing-client"),
        config: BlazegraphViewsConfig,
        baseUri: BaseUri
    ) =>
      SparqlIndexingAction(views, registry, client, config.syncIndexingTimeout)(baseUri)
  }
}
