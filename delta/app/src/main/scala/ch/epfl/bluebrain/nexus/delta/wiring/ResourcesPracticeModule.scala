package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMinPriority
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesPracticeRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{Resources, ResourcesConfig, ResourcesPractice, ValidateResource}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import distage.ModuleDef
import izumi.distage.model.definition.Id
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Resources practice wiring
  */
object ResourcesPracticeModule extends ModuleDef {

  make[ResourcesPractice].from {
    (
        resources: Resources,
        validate: ValidateResource,
        fetchContext: FetchContext[ContextRejection],
        contextResolution: ResolverContextResolution,
        api: JsonLdApi,
        clock: Clock[UIO],
        uuidF: UUIDF
    ) =>
      ResourcesPractice(
        resources.fetch(_, _, None),
        validate,
        fetchContext.mapRejection(ProjectContextRejection),
        contextResolution
      )(api, clock, uuidF)
  }

  make[ResourcesPracticeRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        schemas: Schemas,
        resourcesPractice: ResourcesPractice,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        config: ResourcesConfig
    ) =>
      ResourcesPracticeRoutes(
        identities,
        aclCheck,
        schemas,
        resourcesPractice,
        schemeDirectives
      )(
        baseUri,
        s,
        cr,
        ordering,
        config.decodingOption
      )
  }

  many[PriorityRoute].add { (route: ResourcesPracticeRoutes) =>
    PriorityRoute(pluginsMinPriority - 1, route.routes, requiresStrictEntity = true)
  }

}
