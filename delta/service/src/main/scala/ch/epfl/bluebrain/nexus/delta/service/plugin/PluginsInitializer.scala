package ch.epfl.bluebrain.nexus.delta.service.plugin

import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.PluginInitializationError
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef, PluginInfo}
import distage.{Injector, Roots}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.{IO, Task}

object PluginsInitializer {

  /**
    * Initializes the passed plugins
    */
  def apply(
      serviceModule: ModuleDef,
      pluginsDef: List[PluginDef]
  ): IO[PluginInitializationError, (List[Plugin], Locator)] = {
    val pluginsInfoModule = new ModuleDef {
      make[List[PluginInfo]].from(pluginsDef.map(_.info))
    }
    val appModules        = (serviceModule :: pluginsInfoModule :: pluginsDef.map(_.module)).merge
    Injector()
      .produceF[Task](appModules, Roots.Everything)
      .use(locator => IO.traverse(pluginsDef)(_.initialize(locator)).map(_ -> locator))
      .mapError(e => PluginInitializationError(e.getMessage))
  }
}
