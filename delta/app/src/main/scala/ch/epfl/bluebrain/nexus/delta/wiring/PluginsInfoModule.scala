package ch.epfl.bluebrain.nexus.delta.wiring

import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.PluginsInfoRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginInfo
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities}
import izumi.distage.model.definition.ModuleDef
import monix.execution.Scheduler

/**
  * Plugins info module wiring config.
  */
// $COVERAGE-OFF$
object PluginsInfoModule extends ModuleDef {

  make[PluginsInfoRoutes].from {
    (
        cfg: AppConfig,
        identities: Identities,
        acls: Acls,
        info: List[PluginInfo],
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) => new PluginsInfoRoutes(identities, acls, info)(cfg.http.baseUri, s, cr, ordering)
  }

}
// $COVERAGE-ON$
