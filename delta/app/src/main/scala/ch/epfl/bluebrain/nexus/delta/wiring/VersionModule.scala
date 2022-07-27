package ch.epfl.bluebrain.nexus.delta.wiring

import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.dependency.PostgresServiceDependency
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.VersionRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.{PriorityRoute, ServiceDependency}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

/**
  * Version module wiring config.
  */
// $COVERAGE-OFF$
object VersionModule extends ModuleDef {

  many[ServiceDependency].add { (xas: Transactors) => new PostgresServiceDependency(xas) }

  make[VersionRoutes].from {
    (
        cfg: AppConfig,
        identities: Identities,
        aclCheck: AclCheck,
        plugins: List[PluginDescription],
        dependencies: Set[ServiceDependency],
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      VersionRoutes(identities, aclCheck, plugins, dependencies, cfg.description)(cfg.http.baseUri, s, cr, ordering)
  }

  many[PriorityRoute].add { (route: VersionRoutes) =>
    PriorityRoute(pluginsMaxPriority + 1, route.routes, requiresStrictEntity = true)
  }

}
// $COVERAGE-ON$
