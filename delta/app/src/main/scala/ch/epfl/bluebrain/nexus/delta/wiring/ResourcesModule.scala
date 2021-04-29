package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMinPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Event}
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

  make[Resources].fromEffect {
    (
        config: AppConfig,
        eventLog: EventLog[Envelope[ResourceEvent]],
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        resolvers: Resolvers,
        schemas: Schemas,
        resolverContextResolution: ResolverContextResolution,
        as: ActorSystem[Nothing],
        clock: Clock[UIO],
        uuidF: UUIDF
    ) =>
      ResourcesImpl(
        organizations,
        projects,
        ResourceResolution.schemaResource(acls, resolvers, schemas),
        resolverContextResolution,
        config.resources.aggregate,
        eventLog
      )(uuidF, as, clock)
  }

  make[ResolverContextResolution].from {
    (acls: Acls, resolvers: Resolvers, resources: Resources, rcr: RemoteContextResolution @Id("aggregate")) =>
      ResolverContextResolution(acls, resolvers, resources, rcr)
  }
  make[SseEventLog].from(
    (eventLog: EventLog[Envelope[Event]], orgs: Organizations, projects: Projects, exchanges: Set[EventExchange]) =>
      SseEventLog(eventLog, orgs, projects, exchanges)
  )

  make[ResourcesRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        resources: Resources,
        sseEventLog: SseEventLog,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new ResourcesRoutes(identities, acls, organizations, projects, resources, sseEventLog)(baseUri, s, cr, ordering)
  }

  many[ApiMappings].add(Resources.mappings)

  many[PriorityRoute].add { (route: ResourcesRoutes) => PriorityRoute(pluginsMinPriority - 1, route.routes) }

  many[ReferenceExchange].add { (resources: Resources) =>
    Resources.referenceExchange(resources)
  }

  make[ResourceEventExchange]
  many[EventExchange].ref[ResourceEventExchange]
}
