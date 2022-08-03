package ch.epfl.bluebrain.nexus.delta.wiring

import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.EventsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.sse.{SseEncoder, SseEventLog}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

/**
  * Events wiring
  */
object EventsModule extends ModuleDef {

  make[SseEventLog].fromEffect {
    (
        config: AppConfig,
        organizations: Organizations,
        projects: Projects,
        sseEncoders: Set[SseEncoder[_]],
        xas: Transactors,
        jo: JsonKeyOrdering
    ) =>
      SseEventLog(
        sseEncoders,
        organizations.fetch(_).void,
        projects.fetch(_).map { p => (p.value.organizationUuid, p.value.uuid) },
        config.sse,
        xas
      )(jo)
  }

  make[EventsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        sseEventLog: SseEventLog,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new EventsRoutes(identities, aclCheck, sseEventLog, schemeDirectives)(baseUri, s, cr, ordering)
  }

  many[PriorityRoute].add { (route: EventsRoutes) =>
    PriorityRoute(pluginsMaxPriority + 11, route.routes, requiresStrictEntity = true)
  }
}
