package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResolversRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Resolver, ResolverEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Resolvers wiring
  */
object ResolversModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[Resolvers].from {
    (
        fetchContext: FetchContext[ContextRejection],
        resolverContextResolution: ResolverContextResolution,
        config: AppConfig,
        xas: Transactors,
        api: JsonLdApi,
        clock: Clock[IO],
        uuidF: UUIDF
    ) =>
      ResolversImpl(
        fetchContext.mapRejection(ProjectContextRejection),
        resolverContextResolution,
        config.resolvers,
        xas
      )(api, clock, uuidF)
  }

  make[MultiResolution].from {
    (
        aclCheck: AclCheck,
        fetchContext: FetchContext[ContextRejection],
        resolvers: Resolvers,
        shifts: ResourceShifts
    ) =>
      MultiResolution(
        fetchContext.mapRejection(ProjectContextRejection),
        ResolverResolution(aclCheck, resolvers, shifts, excludeDeprecated = false)
      )
  }

  make[ResolversRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        resolvers: Resolvers,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: AggregateIndexingAction,
        shift: Resolver.Shift,
        multiResolution: MultiResolution,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new ResolversRoutes(
        identities,
        aclCheck,
        resolvers,
        multiResolution,
        schemeDirectives,
        indexingAction(_, _, _)(shift)
      )(
        baseUri,
        cr,
        ordering,
        fusionConfig
      )
  }

  many[SseEncoder[_]].add { base: BaseUri => ResolverEvent.sseEncoder(base) }

  many[ScopedEventMetricEncoder[_]].add { ResolverEvent.resolverEventMetricEncoder }

  make[ResolverScopeInitialization].from { (resolvers: Resolvers, serviceAccount: ServiceAccount, config: AppConfig) =>
    ResolverScopeInitialization(resolvers, serviceAccount, config.resolvers.defaults)
  }
  many[ScopeInitialization].ref[ResolverScopeInitialization]

  many[ApiMappings].add(Resolvers.mappings)

  many[ResourceToSchemaMappings].add(Resolvers.resourcesToSchemas)

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/resolvers-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      resolversCtx     <- ContextValue.fromFile("contexts/resolvers.json")
      resolversMetaCtx <- ContextValue.fromFile("contexts/resolvers-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.resolvers         -> resolversCtx,
      contexts.resolversMetadata -> resolversMetaCtx
    )
  )
  many[PriorityRoute].add { (route: ResolversRoutes) =>
    PriorityRoute(pluginsMaxPriority + 9, route.routes, requiresStrictEntity = true)
  }

  make[Resolver.Shift].from { (resolvers: Resolvers, base: BaseUri) =>
    Resolver.shift(resolvers)(base)
  }

  many[ResourceShift[_, _, _]].ref[Resolver.Shift]
}
