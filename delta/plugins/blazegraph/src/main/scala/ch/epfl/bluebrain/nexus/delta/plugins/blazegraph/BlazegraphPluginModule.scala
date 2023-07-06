package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, schema => viewsSchemaId, BlazegraphView, BlazegraphViewEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.{BlazegraphSlowQueryDeleter, BlazegraphSlowQueryLogger, BlazegraphSlowQueryStore}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
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
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projections
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{ReferenceRegistry, Supervisor}
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Blazegraph plugin wiring
  */
class BlazegraphPluginModule(priority: Int, appConfig: Config) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  private val config: BlazegraphViewsConfig = BlazegraphViewsConfig.load(appConfig)
  make[BlazegraphViewsConfig].from { config }

  make[HttpClient].named("http-indexing-client").from { (as: ActorSystem[Nothing], sc: Scheduler) =>
    HttpClient()(config.indexingClient, as.classicSystem, sc)
  }

  make[BlazegraphSlowQueryStore].from { (xas: Transactors) =>
    BlazegraphSlowQueryStore(
      xas
    )
  }

  make[BlazegraphSlowQueryDeleter].fromEffect { (supervisor: Supervisor, store: BlazegraphSlowQueryStore) =>
    BlazegraphSlowQueryDeleter.start(
      supervisor,
      store,
      config.slowQueries.logTtl,
      config.slowQueries.deleteExpiredLogsEvery
    )
  }

  make[BlazegraphSlowQueryLogger].from { (store: BlazegraphSlowQueryStore) =>
    BlazegraphSlowQueryLogger(store, config.slowQueries.slowQueryThreshold)
  }

  make[BlazegraphClient].named("blazegraph-indexing-client").from {
    (
        client: HttpClient @Id("http-indexing-client"),
        as: ActorSystem[Nothing]
    ) =>
      BlazegraphClient(client, config.base, config.credentials, config.queryTimeout)(as.classicSystem)
  }

  make[HttpClient].named("http-query-client").from { (as: ActorSystem[Nothing], sc: Scheduler) =>
    HttpClient()(config.queryClient, as.classicSystem, sc)
  }

  make[BlazegraphClient].named("blazegraph-query-client").from {
    (
        client: HttpClient @Id("http-query-client"),
        as: ActorSystem[Nothing]
    ) =>
      BlazegraphClient(client, config.base, config.credentials, config.queryTimeout)(as.classicSystem)
  }

  make[ValidateBlazegraphView].from {
    (
        permissions: Permissions,
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
          xas: Transactors,
          api: JsonLdApi,
          clock: Clock[UIO],
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
        )(api, clock, uuidF)
    }

  if (config.indexingEnabled) {
    make[BlazegraphCoordinator].fromEffect {
      (
          views: BlazegraphViews,
          graphStream: GraphResourceStream,
          registry: ReferenceRegistry,
          supervisor: Supervisor,
          client: BlazegraphClient @Id("blazegraph-indexing-client"),
          baseUri: BaseUri
      ) =>
        BlazegraphCoordinator(
          views,
          graphStream,
          registry,
          supervisor,
          client,
          config.batch
        )(baseUri)
    }
  }

  make[BlazegraphViewsQuery].fromEffect {
    (
        aclCheck: AclCheck,
        fetchContext: FetchContext[ContextRejection],
        views: BlazegraphViews,
        client: BlazegraphClient @Id("blazegraph-query-client"),
        slowQueryLogger: BlazegraphSlowQueryLogger,
        xas: Transactors
    ) =>
      BlazegraphViewsQuery(
        aclCheck,
        fetchContext.mapRejection(ProjectContextRejection),
        views,
        client,
        slowQueryLogger,
        config.prefix,
        xas
      )
  }

  make[BlazegraphViewsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: BlazegraphViews,
        projections: Projections,
        viewsQuery: BlazegraphViewsQuery,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: IndexingAction @Id("aggregate"),
        shift: BlazegraphView.Shift,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new BlazegraphViewsRoutes(
        views,
        viewsQuery,
        identities,
        aclCheck,
        projections,
        schemeDirectives,
        indexingAction(_, _, _)(shift, cr)
      )(
        baseUri,
        s,
        cr,
        ordering,
        config.pagination,
        fusionConfig
      )
  }

  make[BlazegraphScopeInitialization].from { (views: BlazegraphViews, serviceAccount: ServiceAccount) =>
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

  many[PriorityRoute].add { (route: BlazegraphViewsRoutes) =>
    PriorityRoute(priority, route.routes, requiresStrictEntity = true)
  }

  many[ServiceDependency].add { (client: BlazegraphClient @Id("blazegraph-indexing-client")) =>
    new BlazegraphServiceDependency(client)
  }

  many[IndexingAction].add {
    (
        views: BlazegraphViews,
        registry: ReferenceRegistry,
        client: BlazegraphClient @Id("blazegraph-indexing-client"),
        baseUri: BaseUri
    ) =>
      BlazegraphIndexingAction(views, registry, client, config.syncIndexingTimeout)(baseUri)
  }

  make[BlazegraphView.Shift].from { (views: BlazegraphViews, base: BaseUri) =>
    BlazegraphView.shift(views)(base)
  }

  many[ResourceShift[_, _, _]].ref[BlazegraphView.Shift]

}
