package ai.senscience.nexus.delta.wiring

import ai.senscience.nexus.delta.Main.pluginsMaxPriority
import ai.senscience.nexus.delta.config.AppConfig
import ai.senscience.nexus.delta.routes.{ElemRoutes, EventsRoutes}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.sse.{SseElemStream, SseEncoder, SseEventLog}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.query.ElemStreaming
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
        sseEncoders: Set[SseEncoder[?]],
        xas: Transactors,
        jo: JsonKeyOrdering
    ) =>
      SseEventLog(
        sseEncoders,
        organizations.fetch(_).void,
        projects.fetch(_).void,
        config.sse,
        xas
      )(jo)
  }

  make[SseElemStream].from { (elemStreaming: ElemStreaming) => SseElemStream(elemStreaming) }

  make[EventsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        sseEventLog: SseEventLog,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new EventsRoutes(identities, aclCheck, sseEventLog)(baseUri, cr, ordering)
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
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new ElemRoutes(identities, aclCheck, sseElemStream, schemeDirectives)(baseUri, cr, ordering)
  }

  many[PriorityRoute].add { (route: ElemRoutes) =>
    PriorityRoute(pluginsMaxPriority + 12, route.routes, requiresStrictEntity = true)
  }
}
