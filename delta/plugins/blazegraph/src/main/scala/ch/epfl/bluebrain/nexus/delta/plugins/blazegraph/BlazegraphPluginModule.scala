package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphOnEventInstant
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, schema => viewsSchemaId, BlazegraphViewEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.OnEventInstant
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Blazegraph plugin wiring
  */
class BlazegraphPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[BlazegraphViewsConfig].from { BlazegraphViewsConfig.load(_) }

  make[HttpClient].named("http-indexing-client").from {
    (cfg: BlazegraphViewsConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
      HttpClient()(cfg.indexingClient, as.classicSystem, sc)
  }

  make[BlazegraphClient].named("blazegraph-indexing-client").from {
    (cfg: BlazegraphViewsConfig, client: HttpClient @Id("http-indexing-client"), as: ActorSystem[Nothing]) =>
      BlazegraphClient(client, cfg.base, cfg.credentials, cfg.queryTimeout)(as.classicSystem)
  }

  make[HttpClient].named("http-query-client").from {
    (cfg: BlazegraphViewsConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
      HttpClient()(cfg.queryClient, as.classicSystem, sc)
  }

  make[BlazegraphClient].named("blazegraph-query-client").from {
    (cfg: BlazegraphViewsConfig, client: HttpClient @Id("http-query-client"), as: ActorSystem[Nothing]) =>
      BlazegraphClient(client, cfg.base, cfg.credentials, cfg.queryTimeout)(as.classicSystem)
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

  make[BlazegraphViewsQuery].fromEffect {
    (
        aclCheck: AclCheck,
        fetchContext: FetchContext[ContextRejection],
        views: BlazegraphViews,
        client: BlazegraphClient @Id("blazegraph-query-client"),
        cfg: BlazegraphViewsConfig,
        xas: Transactors
    ) =>
      BlazegraphViewsQuery(aclCheck, fetchContext.mapRejection(ProjectContextRejection), views, client, cfg.prefix, xas)
  }

  make[BlazegraphViewsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: BlazegraphViews,
        viewsQuery: BlazegraphViewsQuery,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: IndexingAction @Id("aggregate"),
        baseUri: BaseUri,
        cfg: BlazegraphViewsConfig,
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
        // TODO add progress stats
        null,
        // TODO add the way to restart ES views
        (_, _) => UIO.unit,
        schemeDirectives,
        indexingAction
      )(
        baseUri,
        s,
        cr,
        ordering,
        cfg.pagination,
        fusionConfig
      )
  }

  make[BlazegraphScopeInitialization]
  many[ScopeInitialization].ref[BlazegraphScopeInitialization]

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

  many[IndexingAction].addValue {
    new BlazegraphIndexingAction()
  }

  make[BlazegraphOnEventInstant]
  many[OnEventInstant].ref[BlazegraphOnEventInstant]

}
