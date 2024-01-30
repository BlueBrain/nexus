package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMinPriority
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesTrialRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{Resources, ResourcesConfig, ResourcesTrial, ValidateResource}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import distage.ModuleDef
import izumi.distage.model.definition.Id

/**
  * Resources trial wiring
  */
object ResourcesTrialModule extends ModuleDef {

  make[ResourcesTrial].from {
    (
        resources: Resources,
        validate: ValidateResource,
        fetchContext: FetchContext,
        contextResolution: ResolverContextResolution,
        api: JsonLdApi,
        clock: Clock[IO],
        uuidF: UUIDF
    ) =>
      ResourcesTrial(
        resources.fetchState(_, _, None),
        validate,
        fetchContext,
        contextResolution,
        clock
      )(api, uuidF)
  }

  make[ResourcesTrialRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        schemas: Schemas,
        resourcesTrial: ResourcesTrial,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        config: ResourcesConfig
    ) =>
      ResourcesTrialRoutes(
        identities,
        aclCheck,
        schemas,
        resourcesTrial
      )(
        baseUri,
        cr,
        ordering,
        config.decodingOption
      )
  }

  many[PriorityRoute].add { (route: ResourcesTrialRoutes) =>
    PriorityRoute(pluginsMinPriority - 1, route.routes, requiresStrictEntity = true)
  }

}
