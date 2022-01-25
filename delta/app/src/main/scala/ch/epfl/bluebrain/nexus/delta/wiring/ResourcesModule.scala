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
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, EntityType, Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.service.resources.ResourcesImpl.ResourcesAggregate
import ch.epfl.bluebrain.nexus.delta.service.resources.{DataDeletion, ResourceEventExchange, ResourcesImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.{DatabaseCleanup, EventLog}
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
        acls: Acls,
        resolvers: Resolvers,
        schemas: Schemas,
        resourceIdCheck: ResourceIdCheck,
        api: JsonLdApi,
        as: ActorSystem[Nothing],
        clock: Clock[UIO]
    ) =>
      ResourcesImpl.aggregate(
        config.resources.aggregate,
        ResourceResolution.schemaResource(acls, resolvers, schemas),
        resourceIdCheck
      )(api, as, clock)
  }

  many[ResourcesDeletion].add { (agg: ResourcesAggregate, resources: Resources, dbCleanup: DatabaseCleanup) =>
    DataDeletion(agg, resources, dbCleanup)
  }

  make[Resources].from {
    (
        eventLog: EventLog[Envelope[ResourceEvent]],
        agg: ResourcesAggregate,
        organizations: Organizations,
        projects: Projects,
        api: JsonLdApi,
        resolverContextResolution: ResolverContextResolution,
        uuidF: UUIDF
    ) =>
      ResourcesImpl(organizations, projects, agg, resolverContextResolution, eventLog)(api, uuidF)
  }

  make[ResolverContextResolution].from {
    (acls: Acls, resolvers: Resolvers, resources: Resources, rcr: RemoteContextResolution @Id("aggregate")) =>
      ResolverContextResolution(acls, resolvers, resources, rcr)
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
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        resources: Resources,
        indexingAction: IndexingAction @Id("aggregate"),
        sseEventLog: SseEventLog @Id("resources"),
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new ResourcesRoutes(identities, acls, organizations, projects, resources, sseEventLog, indexingAction)(
        baseUri,
        s,
        cr,
        ordering
      )
  }

  many[ApiMappings].add(Resources.mappings)

  many[PriorityRoute].add { (route: ResourcesRoutes) =>
    PriorityRoute(pluginsMinPriority - 1, route.routes, requiresStrictEntity = false)
  }

  many[ReferenceExchange].add { (resources: Resources) =>
    Resources.referenceExchange(resources)
  }

  make[ResourceEventExchange]
  many[EventExchange].ref[ResourceEventExchange]
  many[EventExchange].named("resources").ref[ResourceEventExchange]
  many[EntityType].add(EntityType(Resources.moduleType))

}
