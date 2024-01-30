package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ExportRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.Exporter
import izumi.distage.model.definition.{Id, ModuleDef}

// $COVERAGE-OFF$
object ExportModule extends ModuleDef {

  make[Exporter].fromEffect { (config: AppConfig, clock: Clock[IO], xas: Transactors) =>
    Exporter(config.`export`, clock, xas)
  }

  make[ExportRoutes].from {
    (
        cfg: AppConfig,
        identities: Identities,
        aclCheck: AclCheck,
        exporter: Exporter,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new ExportRoutes(identities, aclCheck, exporter)(
        cfg.http.baseUri,
        cr,
        ordering
      )
  }

  many[PriorityRoute].add { (route: ExportRoutes) =>
    PriorityRoute(pluginsMaxPriority + 1, route.routes, requiresStrictEntity = true)
  }

}
// $COVERAGE-ON$
