package ch.epfl.bluebrain.nexus.delta.wiring

import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.SupervisionRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Supervisor
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

/**
  * Supervision module wiring config.
  */
// $COVERAGE-OFF$
object SupervisionModule extends ModuleDef {
  implicit private val classLoader = getClass.getClassLoader

  make[SupervisionRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        supervisor: Supervisor,
        baseUri: BaseUri,
        s: Scheduler,
        rc: RemoteContextResolution @Id("aggregate"),
        jo: JsonKeyOrdering
    ) => new SupervisionRoutes(identities, aclCheck, supervisor.getRunningProjections)(baseUri, s, rc, jo)
  }

  many[RemoteContextResolution].addEffect(
    for {
      supervisionCtx <- ContextValue.fromFile("contexts/supervision.json")
    } yield RemoteContextResolution.fixed(
      contexts.supervision -> supervisionCtx
    )
  )

  many[PriorityRoute].add { (route: SupervisionRoutes) =>
    PriorityRoute(pluginsMaxPriority + 12, route.routes, requiresStrictEntity = true)
  }
}
// $COVERAGE-ON$
