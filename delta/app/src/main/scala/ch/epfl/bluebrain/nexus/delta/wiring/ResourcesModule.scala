package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMinPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, Projects}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.service.resources.ResourcesImpl.ResourcesAggregate
import ch.epfl.bluebrain.nexus.delta.service.resources.{ResourceEventExchange, ResourcesImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Resources wiring
  */
object ResourcesModule extends ModuleDef {

  make[EventLog[Envelope[ResourceEvent]]].fromEffect { databaseEventLog[ResourceEvent](_, _) }

  make[ResourcesAggregate].fromEffect {
    (
        config: AppConfig,
        aclCheck: AclCheck,
        resolvers: Resolvers,
        schemas: Schemas,
        resourceIdCheck: ResourceIdCheck,
        api: JsonLdApi,
        as: ActorSystem[Nothing],
        clock: Clock[UIO]
    ) =>
      ResourcesImpl.aggregate(
        config.resources.aggregate,
        ResourceResolution.schemaResource(aclCheck, resolvers, schemas),
        resourceIdCheck
      )(api, as, clock)
  }

  make[Resources].from {
    (
        eventLog: EventLog[Envelope[ResourceEvent]],
        agg: ResourcesAggregate,
        fetchContext: FetchContext[ContextRejection],
        api: JsonLdApi,
        resolverContextResolution: ResolverContextResolution,
        uuidF: UUIDF
    ) =>
      ResourcesImpl(fetchContext.mapRejection(ProjectContextRejection), agg, resolverContextResolution, eventLog)(
        api,
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

  many[ReferenceExchange].add { (resources: Resources) =>
    Resources.referenceExchange(resources)
  }

  make[ResourceEventExchange]
  many[EventExchange].ref[ResourceEventExchange]
  many[EventExchange].named("resources").ref[ResourceEventExchange]

}
