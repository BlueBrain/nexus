package ch.epfl.bluebrain.nexus.delta.plugin

import cats.effect.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.PluginInitializationError
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import com.typesafe.scalalogging.Logger
import distage.{Injector, Roots}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import izumi.distage.modules.DefaultModule
import monix.bio.{IO, Task}

object WiringInitializer {

  private val logger = Logger[WiringInitializer.type]

  /**
    * Combines the [[ModuleDef]] of the passed ''serviceModule'' with the ones provided by the plugins. Afterwards
    * initializes the [[Plugin]] s and the [[Locator]].
    */
  def apply(
      serviceModule: ModuleDef,
      pluginsDef: List[PluginDef]
  ): Resource[Task, (List[Plugin], Locator)] = {
    val pluginsInfoModule = new ModuleDef { make[List[PluginDef]].from(pluginsDef) }
    val appModules        = (serviceModule :: pluginsInfoModule :: pluginsDef.map(_.module)).merge

    // workaround for: java.lang.NoClassDefFoundError: zio/blocking/package$Blocking$Service
    implicit val defaultModule: DefaultModule[Task] = DefaultModule.empty
    Injector[Task]()
      .produce(appModules, Roots.Everything)
      .toCats
      .evalMap { locator =>
        IO.traverse(pluginsDef) { plugin =>
          Task.delay(logger.info(s"Initializing plugin ${plugin.info.name}...")) >>
            plugin.initialize(locator).tapEval { _ =>
              Task.delay(logger.info(s"Plugin ${plugin.info.name} initialized."))
            }
        }.map(_ -> locator)
          .mapError(e => PluginInitializationError(e.getMessage))
      }
  }
}
