package ch.epfl.bluebrain.nexus.delta.service.plugin

import java.io.{File, FilenameFilter}

import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.{MultiplePluginDefClassesFound, PluginDefClassNotFound, PluginInitializationError}
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginLoader.PluginLoaderConfig
import distage.{Injector, Roots}
import io.github.classgraph.ClassGraph
import izumi.distage.model.definition.ModuleDef
import monix.bio.{IO, Task}

import scala.jdk.CollectionConverters._

class PluginLoader(loaderConfig: PluginLoaderConfig) {

  private def loadPluginDef(jar: File): IO[PluginError, PluginDef] = {
    val pluginClassLoader = new PluginClassLoader(jar.toURI.toURL, this.getClass.getClassLoader)
    val pluginDefClasses  = new ClassGraph()
      .overrideClassLoaders(pluginClassLoader)
      .enableAllInfo()
      .scan()
      .getClassesImplementing("ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef")
      .getNames
      .asScala
      .toList

    pluginDefClasses match {
      case pluginDef :: Nil =>
        IO.pure(pluginClassLoader.create[PluginDef](pluginDef, println)())
      case Nil              => IO.raiseError(PluginDefClassNotFound(jar))
      case multiple         =>
        IO.raiseError(MultiplePluginDefClassesFound(jar, multiple.toSet))

    }
  }

  /**
    * Load and initialize plugins.
    *
    * @param serviceModule  distage module defining dependencies provided by the service
    * @return List of initialized plugins.
    */
  def loadAndStartPlugins(serviceModule: ModuleDef): IO[PluginError, List[Plugin]] = {

    val pluginJars = loaderConfig.pluginDir match {
      case None      => List.empty
      case Some(dir) =>
        val pluginDir = new File(dir)
        pluginDir
          .listFiles(new FilenameFilter {
            override def accept(dir: File, name: String): Boolean = name.endsWith(".jar")
          })
          .toList
    }

    for {
      pluginsDefs <- IO.sequence(pluginJars.map(loadPluginDef))
      moduleDefs   = pluginsDefs.map(_.module)
      fullModule   = (serviceModule :: moduleDefs).merge
      locator     <- Injector()
                       .produceF[Task](fullModule, Roots.Everything)
                       .unsafeGet()
                       .mapError(e => PluginInitializationError(e.getMessage))
      plugins     <-
        IO.sequence(
          pluginsDefs.map(pInfo => pInfo.initialise(locator).mapError(e => PluginInitializationError(e.getMessage)))
        )
    } yield plugins

  }

}

object PluginLoader {

  def apply(loaderConfig: PluginLoaderConfig): PluginLoader = new PluginLoader(loaderConfig)

  final case class PluginLoaderConfig(pluginDir: Option[String])
}
