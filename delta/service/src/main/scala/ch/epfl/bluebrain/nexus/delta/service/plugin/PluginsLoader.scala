package ch.epfl.bluebrain.nexus.delta.service.plugin

import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.MultiplePluginDefClassesFound
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginsLoader.PluginLoaderConfig
import com.typesafe.scalalogging.Logger
import io.github.classgraph.ClassGraph
import monix.bio.IO

import java.io.{File, FilenameFilter}
import scala.jdk.CollectionConverters._

/**
  * Class responsible for loading [[PluginDef]]s.
  *
  * It looks for jar files in the passed directories in [[loaderConfig]] and tries to load a [[PluginDef]]
  * from each jar file found.
  *
  * @param loaderConfig [[PluginsLoader]] configuration
  */
class PluginsLoader(loaderConfig: PluginLoaderConfig) {
  private val logger: Logger = Logger[PluginsLoader]

  private val parentClassLoader = this.getClass.getClassLoader

  /**
    * Loads all the available [[PluginDef]] from each of the discovered jar files
    */
  def load: IO[PluginError, (PluginsClassLoader, List[PluginDef])] =
    for {
      jarFiles                   <- IO.delay(loaderConfig.directories.flatMap(loadFiles)).hideErrors
      (pluginsDef, classLoaders) <- IO.traverse(jarFiles)(loadPluginDef).map(_.flatten.sortBy(_._1).unzip)
      accClassLoader              = new PluginsClassLoader(classLoaders, parentClassLoader)
    } yield (accClassLoader, pluginsDef)

  private def loadPluginDef(jar: File): IO[PluginError, Option[(PluginDef, PluginClassLoader)]] =
    for {
      pluginClassLoader <- IO.delay(new PluginClassLoader(jar.toURI.toURL, parentClassLoader)).hideErrors
      pluginDefClasses  <- IO.delay(loadPluginDefClasses(pluginClassLoader)).hideErrors
      pluginDef         <- pluginDefClasses match {
                             case pluginDef :: Nil =>
                               IO.pure(Some(pluginClassLoader.create[PluginDef](pluginDef, println)()))
                             case Nil              =>
                               logger.warn(s"Jar file '$jar' does not contain a 'PluginDef' implementation.")
                               IO.pure(None)
                             case multiple         =>
                               IO.raiseError(MultiplePluginDefClassesFound(jar, multiple.toSet))

                           }
    } yield pluginDef.map(_ -> pluginClassLoader)

  private def loadPluginDefClasses(loader: ClassLoader)                                         =
    new ClassGraph()
      .overrideClassLoaders(loader)
      .enableAllInfo()
      .scan()
      .getClassesImplementing(classOf[PluginDef].getName)
      .getNames
      .asScala
      .toList

  private def loadFiles(directory: String): Seq[File] =
    new File(directory)
      .listFiles(new FilenameFilter {
        override def accept(dir: File, name: String): Boolean = name.endsWith(".jar")
      })
      .toList
}

object PluginsLoader {

  /**
    * Construct a new [[PluginsLoader]] instance.
    *
    * @param loaderConfig [[PluginsLoader]] configuration.
    * @return an instance of [[PluginsLoader]]
    */
  def apply(loaderConfig: PluginLoaderConfig): PluginsLoader = new PluginsLoader(loaderConfig)

  /**
    * [[PluginsLoader]] configuration.
    *
    * @param directories  directories where to load [[Plugin]]s from.
    */
  final case class PluginLoaderConfig(directories: List[String])

  object PluginLoaderConfig {
    final def apply(directories: String*): PluginLoaderConfig =
      PluginLoaderConfig(directories.toList)
  }
}
