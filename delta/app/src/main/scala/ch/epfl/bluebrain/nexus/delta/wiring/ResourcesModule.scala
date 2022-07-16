package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMinPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, Projects}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.{ResolverContextResolution, Resolvers, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{Resources, ResourcesImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Resources wiring
  */
object ResourcesModule extends ModuleDef {

  make[Resources].from {
    (
        aclCheck: AclCheck,
        resolvers: Resolvers,
        schemas: Schemas,
        fetchContext: FetchContext[ContextRejection],
        config: AppConfig,
        resolverContextResolution: ResolverContextResolution,
        api: JsonLdApi,
        xas: Transactors,
        clock: Clock[UIO],
        uuidF: UUIDF
    ) =>
      ResourcesImpl(
        ResourceResolution.schemaResource(aclCheck, resolvers, schemas),
        fetchContext.mapRejection(ProjectContextRejection),
        resolverContextResolution,
        config.resources,
        xas
      )(
        api,
        clock,
        uuidF
      )
  }

  make[ResolverContextResolution].from {
    (aclCheck: AclCheck, resolvers: Resolvers, resources: Resources, rcr: RemoteContextResolution @Id("aggregate")) =>
      ResolverContextResolution(aclCheck, resolvers, resources, rcr)
  }
  make[SseEventLog]
    .named("resources")
    .from(
      (
          eventLog: EventLog[Envelope[Event]],
          orgs: Organizations,
          projects: Projects,
          exchanges: Set[EventExchange] @Id("resources")
      ) => SseEventLog(eventLog, orgs, projects, exchanges)
    )

  make[ResourcesRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        resources: Resources,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: IndexingAction @Id("aggregate"),
        sseEventLog: SseEventLog @Id("resources"),
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new ResourcesRoutes(identities, aclCheck, resources, schemeDirectives, sseEventLog, indexingAction)(
        baseUri,
        s,
        cr,
        ordering,
        fusionConfig
      )
  }

  many[ApiMappings].add(Resources.mappings)

  many[PriorityRoute].add { (route: ResourcesRoutes) =>
    PriorityRoute(pluginsMinPriority - 1, route.routes, requiresStrictEntity = true)
  }

}
