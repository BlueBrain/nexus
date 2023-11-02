package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.{ElemRoutes, EventsRoutes}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.sse.{SseElemStream, SseEncoder, SseEventLog}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import izumi.distage.model.definition.{Id, ModuleDef}

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
        jo: JsonKeyOrdering,
        timer: Timer[IO]
    ) =>
      SseEventLog(
        sseEncoders,
        organizations.fetch(_).void.toBIO[OrganizationRejection],
        projects.fetch(_).map { p => (p.value.organizationUuid, p.value.uuid) },
        config.sse,
        xas
      )(jo, timer)
  }

  make[SseElemStream].from { (qc: QueryConfig, xas: Transactors, timer: Timer[IO]) =>
    SseElemStream(qc, xas)(timer)
  }

  make[EventsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        sseEventLog: SseEventLog,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        c: ContextShift[IO],
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new EventsRoutes(identities, aclCheck, sseEventLog, schemeDirectives)(baseUri, c, cr, ordering)
  }

  many[PriorityRoute].add { (route: EventsRoutes) =>
    PriorityRoute(pluginsMaxPriority + 11, route.routes, requiresStrictEntity = true)
  }

  make[ElemRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        sseElemStream: SseElemStream,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        contextShift: ContextShift[IO],
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new ElemRoutes(identities, aclCheck, sseElemStream, schemeDirectives)(baseUri, cr, ordering, contextShift)
  }

  many[PriorityRoute].add { (route: ElemRoutes) =>
    PriorityRoute(pluginsMaxPriority + 12, route.routes, requiresStrictEntity = true)
  }
}
