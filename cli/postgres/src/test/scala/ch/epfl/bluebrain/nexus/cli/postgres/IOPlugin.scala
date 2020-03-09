package ch.epfl.bluebrain.nexus.cli.postgres

import izumi.distage.effect.modules.CatsDIEffectModule
import izumi.distage.plugins.PluginDef

object IOPlugin extends PluginDef {
  include(CatsDIEffectModule)
}
