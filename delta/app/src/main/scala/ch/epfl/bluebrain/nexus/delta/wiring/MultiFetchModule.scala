package ch.epfl.bluebrain.nexus.delta.wiring

import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.MultiFetchRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.MultiFetch
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchRequest
import ch.epfl.bluebrain.nexus.delta.sdk.{PriorityRoute, ResourceShifts}
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import distage.ModuleDef
import izumi.distage.model.definition.Id
import monix.execution.Scheduler

object MultiFetchModule extends ModuleDef {

  make[MultiFetch].from {
    (
        aclCheck: AclCheck,
        shifts: ResourceShifts
    ) =>
      MultiFetch(
        aclCheck,
        (input: MultiFetchRequest.Input) => shifts.fetch(input.id, input.project).toUIO
      )
  }

  make[MultiFetchRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        multiFetch: MultiFetch,
        baseUri: BaseUri,
        rcr: RemoteContextResolution @Id("aggregate"),
        jko: JsonKeyOrdering,
        sc: Scheduler
    ) =>
      new MultiFetchRoutes(identities, aclCheck, multiFetch)(baseUri, rcr, jko, sc)
  }

  many[PriorityRoute].add { (route: MultiFetchRoutes) =>
    PriorityRoute(pluginsMaxPriority + 13, route.routes, requiresStrictEntity = true)
  }

}
