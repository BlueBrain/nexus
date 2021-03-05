package ch.epfl.bluebrain.nexus.delta.service.plugin

import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.PluginInitializationError
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import distage.{Injector, Roots}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import izumi.distage.modules.DefaultModule
import monix.bio.{IO, Task}

object WiringInitializer {

  /**
    * Combines the [[ModuleDef]] of the passed ''serviceModule'' with the ones provided by the plugins.
    * Afterwards initializes the [[Plugin]]s and the [[Locator]].
    */
  def apply(
      serviceModule: ModuleDef,
      pluginsDef: List[PluginDef]
  ): IO[PluginInitializationError, (List[Plugin], Locator)] = {
    val pluginsInfoModule = new ModuleDef { make[List[PluginDef]].from(pluginsDef) }
    val appModules        = (serviceModule :: pluginsInfoModule :: pluginsDef.map(_.module)).merge

    // workaround for: java.lang.NoClassDefFoundError: zio/blocking/package$Blocking$Service
    implicit val defaultModule: DefaultModule[Task] = DefaultModule.empty
    Injector[Task]()
      .produce(appModules, Roots.Everything)
      .use(locator => IO.traverse(pluginsDef)(_.initialize(locator)).map(_ -> locator))
      .mapError(e => PluginInitializationError(e.getMessage))
  }
}
