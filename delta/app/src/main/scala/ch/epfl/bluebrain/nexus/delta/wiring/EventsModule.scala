package ch.epfl.bluebrain.nexus.delta.wiring

import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.EventsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

/**
  * Events wiring
  */
object EventsModule extends ModuleDef {

  make[EventsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        projects: Projects,
        sseEventLog: SseEventLog,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new EventsRoutes(identities, aclCheck, projects, sseEventLog)(baseUri, s, cr, ordering)
  }

  make[SseEventLog]
    .from(
      (
          eventLog: EventLog[Envelope[Event]],
          orgs: Organizations,
          projects: Projects,
          exchanges: Set[EventExchange]
      ) => SseEventLog(eventLog, orgs, projects, exchanges)
    )

  many[PriorityRoute].add { (route: EventsRoutes) =>
    PriorityRoute(pluginsMaxPriority + 11, route.routes, requiresStrictEntity = true)
  }
}
