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

/**
  * Class responsible for loading [[Plugin]]s.
  *
  * It looks for jar files in directory specified in [[loaderConfig]] and tries to load a [[Plugin]]
  * from each jar file found.
  *
  * @param loaderConfig [[PluginLoader]] configuration
  */
class PluginLoader(loaderConfig: PluginLoaderConfig) {

  private def loadPluginDef(jar: File): IO[PluginError, PluginDef] = {
    val pluginClassLoader = new PluginClassLoader(jar.toURI.toURL, this.getClass.getClassLoader)
    val pluginDefClasses  = new ClassGraph()
      .overrideClassLoaders(pluginClassLoader)
      .enableAllInfo()
      .scan()
      .getClassesImplementing(classOf[PluginDef].getName)
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
    * Look for jar files in directory specified in [[loaderConfig]] and try to load a [[Plugin]]
    * from each jar file found.
    *
    * @param serviceModule  distage [[ModuleDef]] defining dependencies provided by the service
    * @return List of initialized plugins
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
          pluginsDefs.map(pDef => pDef.initialise(locator).mapError(e => PluginInitializationError(e.getMessage)))
        )
    } yield plugins

  }

}

object PluginLoader {

  /**
    * Construct a new [[PluginLoader]] instance.
    *
    * @param loaderConfig [[PluginLoader]] configuration.
    * @return an instance of [[PluginLoader]]
    */
  def apply(loaderConfig: PluginLoaderConfig): PluginLoader = new PluginLoader(loaderConfig)

  /**
    * [[PluginLoader]] configuration.
    *
    * @param pluginDir  optional directory to load [[Plugin]]s from.
    */
  final case class PluginLoaderConfig(pluginDir: Option[String])
}
