package ch.epfl.bluebrain.nexus.delta.wiring

import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.SupervisionRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Supervisor
import izumi.distage.model.definition.ModuleDef
import monix.execution.Scheduler

/**
 * Supervision module wiring config.
 */
// $COVERAGE-OFF$
object SupervisionModule extends ModuleDef {

  make[SupervisionRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        supervisor: Supervisor,
        baseUri: BaseUri,
        s: Scheduler,
        rc: RemoteContextResolution,
        jo: JsonKeyOrdering
    ) => new SupervisionRoutes(identities, aclCheck, supervisor.getRunningProjections)(baseUri, s, rc, jo)
  }

  // TODO: Add PriorityRoute?
  many[PriorityRoute].add { (route: SupervisionRoutes) =>
    PriorityRoute(pluginsMaxPriority + 3, route.routes, requiresStrictEntity = true)
  }
}
// $COVERAGE-ON$