package ai.senscience.nexus.delta.wiring

import ai.senscience.nexus.delta.Main.pluginsMaxPriority
import ai.senscience.nexus.delta.routes.MultiFetchRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.MultiFetch
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchRequest
import ch.epfl.bluebrain.nexus.delta.sdk.{PriorityRoute, ResourceShifts}
import distage.ModuleDef
import izumi.distage.model.definition.Id

object MultiFetchModule extends ModuleDef {

  make[MultiFetch].from {
    (
        aclCheck: AclCheck,
        shifts: ResourceShifts
    ) =>
      MultiFetch(
        aclCheck,
        (input: MultiFetchRequest.Input) => shifts.fetch(input.id, input.project)
      )
  }

  make[MultiFetchRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        multiFetch: MultiFetch,
        baseUri: BaseUri,
        rcr: RemoteContextResolution @Id("aggregate"),
        jko: JsonKeyOrdering
    ) =>
      new MultiFetchRoutes(identities, aclCheck, multiFetch)(baseUri, rcr, jko)
  }

  many[PriorityRoute].add { (route: MultiFetchRoutes) =>
    PriorityRoute(pluginsMaxPriority + 13, route.routes, requiresStrictEntity = true)
  }

}
