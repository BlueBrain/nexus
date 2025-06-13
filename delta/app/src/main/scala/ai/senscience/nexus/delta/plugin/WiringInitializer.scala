package ai.senscience.nexus.delta.plugin

import cats.effect.{IO, Resource}
import cats.syntax.traverse.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.PluginInitializationError
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import distage.{Injector, Roots}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import izumi.distage.modules.DefaultModule

object WiringInitializer {

  private val logger = Logger[WiringInitializer.type]

  /**
    * Combines the [[ModuleDef]] of the passed ''serviceModule'' with the ones provided by the plugins. Afterwards
    * initializes the [[Plugin]] s and the [[Locator]].
    */
  def apply(
      serviceModule: ModuleDef,
      pluginsDef: List[PluginDef]
  ): Resource[IO, (List[Plugin], Locator)] = {
    val pluginsInfoModule = new ModuleDef { make[List[PluginDef]].from(pluginsDef) }
    val appModules        = (serviceModule :: pluginsInfoModule :: pluginsDef.map(_.module)).merge

    // workaround for: java.lang.NoClassDefFoundError: zio/blocking/package$Blocking$Service
    implicit val defaultModule: DefaultModule[IO] = DefaultModule.empty
    Injector[IO]()
      .produce(appModules, Roots.Everything)
      .toCats
      .evalMap { locator =>
        pluginsDef
          .traverse { plugin =>
            logger.info(s"Initializing plugin ${plugin.info.name}...") >>
              plugin.initialize(locator).flatTap { _ =>
                logger.info(s"Plugin ${plugin.info.name} initialized.")
              }
          }
          .map(_ -> locator)
          .adaptError(e => PluginInitializationError(e.getMessage))
      }
  }
}
