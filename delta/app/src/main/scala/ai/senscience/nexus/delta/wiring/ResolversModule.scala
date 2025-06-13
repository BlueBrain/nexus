package ai.senscience.nexus.delta.wiring

import ai.senscience.nexus.delta.Main.pluginsMaxPriority
import ai.senscience.nexus.delta.config.AppConfig
import ai.senscience.nexus.delta.routes.ResolversRoutes
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.*
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.*
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverEvent
import ch.epfl.bluebrain.nexus.delta.sdk.resources.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.FetchSchema
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Resolvers wiring
  */
object ResolversModule extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

  make[Resolvers].from {
    (
        fetchContext: FetchContext,
        resolverContextResolution: ResolverContextResolution,
        config: AppConfig,
        xas: Transactors,
        clock: Clock[IO],
        uuidF: UUIDF
    ) =>
      ResolversImpl(
        fetchContext,
        resolverContextResolution,
        ValidatePriority.priorityAlreadyExists(xas),
        config.resolvers.eventLog,
        xas,
        clock
      )(uuidF)
  }

  make[MultiResolution].from {
    (
        fetchContext: FetchContext,
        aclCheck: AclCheck,
        resolvers: Resolvers,
        fetchResource: FetchResource,
        fetchSchema: FetchSchema
    ) =>
      MultiResolution(
        fetchContext,
        aclCheck,
        resolvers,
        fetchResource,
        fetchSchema
      )
  }

  make[ResolversRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        resolvers: Resolvers,
        schemeDirectives: DeltaSchemeDirectives,
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
        schemeDirectives
      )(
        baseUri,
        cr,
        ordering,
        fusionConfig
      )
  }

  many[SseEncoder[?]].add { base: BaseUri => ResolverEvent.sseEncoder(base) }

  make[ResolverScopeInitialization].from { (resolvers: Resolvers, serviceAccount: ServiceAccount, config: AppConfig) =>
    ResolverScopeInitialization(resolvers, serviceAccount, config.resolvers.defaults)
  }
  many[ScopeInitialization].ref[ResolverScopeInitialization]

  many[ApiMappings].add(Resolvers.mappings)

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
}
