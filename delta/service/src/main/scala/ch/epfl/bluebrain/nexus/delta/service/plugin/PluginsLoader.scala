package ch.epfl.bluebrain.nexus.delta.service.plugin

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError
import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.{ClassNotFoundError, MultiplePluginDefClassesFound, PluginLoadErrors}
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.service.plugin.PluginsLoader.PluginLoaderConfig
import com.typesafe.scalalogging.Logger
import io.github.classgraph.ClassGraph
import monix.bio.{IO, UIO}

import java.io.{File, FilenameFilter}
import java.lang.reflect.InvocationTargetException
import scala.jdk.CollectionConverters._

/**
  * Class responsible for loading [[PluginDef]] s.
  *
  * It looks for jar files in the passed directories in [[loaderConfig]] and tries to load a [[PluginDef]] from each jar
  * file found.
  *
  * @param loaderConfig
  *   [[PluginsLoader]] configuration
  */
class PluginsLoader(loaderConfig: PluginLoaderConfig) {
  private val logger: Logger = Logger[PluginsLoader]

  private val parentClassLoader = this.getClass.getClassLoader

  /**
    * Loads all the available [[PluginDef]] from each of the discovered jar files. In order to solve class loading
    * issues caused by plugin dependencies, the load operation accumulates all independent plugin classloaders and adds
    * them to a mutable [[PluginsClassLoader]] which is the parent of all plugin class loaders. It traverses all the
    * plugin jar files and attempts to load them in multiple passes where a new pass is attempted if the previous one
    * managed to load some classes but not all.
    */
  def load: IO[PluginError, (ClassLoader, List[PluginDef])] = {
    UIO.delay(loaderConfig.directories.flatMap(loadFiles)).flatMap { jarFiles =>
      // recursively load the jar files, retrying in case of errors if there's at least one plugin loaded per pass
      // this enables handling of plugin dependencies
      IO.tailRecM((jarFiles, new PluginsClassLoader(Nil, parentClassLoader), Nil: List[PluginDef])) {
        case (Nil, cl, plugins)       => IO.pure(Right((cl, plugins)))
        case (remaining, cl, plugins) =>
          // attempt to load the remaining files
          remaining.traverse(file => loadPluginDef(file, cl).attempt.map(v => (file, v))).flatMap { results =>
            // partition the load results into List(file -> error) and List(plugin def -> plugin class loader)
            val partitioned =
              results.foldLeft((Nil: List[(File, PluginError)], Nil: List[(PluginDef, PluginClassLoader)])) {
                case ((errors, loaded), (f, Left(err)))          => ((f, err) :: errors, loaded)
                case ((errors, loaded), (_, Right(None)))        => (errors, loaded)
                case ((errors, loaded), (_, Right(Some(value)))) => (errors, value :: loaded)
              }

            partitioned match {
              // everything was loaded, adding each plugin class loader and return all plugin defs
              case (Nil, loaded)                =>
                UIO.delay {
                  loaded.foreach { case (_, pcl) => cl.addPluginClassLoader(pcl) }
                  Left((Nil, cl, plugins ++ loaded.map { case (pdef, _) => pdef }))
                }
              // nothing resolved, pick the first error and return
              case ((file, error) :: rest, Nil) =>
                IO.raiseError(PluginLoadErrors(NonEmptySet((file, error), rest.toSet)))
              // some new plugins were loaded, but not all, adding the loaded ones and executing another pass
              case (errors, loaded)             =>
                UIO.delay {
                  loaded.foreach { case (_, pcl) => cl.addPluginClassLoader(pcl) }
                  Left((errors.map { case (file, _) => file }, cl, plugins ++ loaded.map { case (pdef, _) => pdef }))
                }
            }
          }
      }
    }
  }

  private def loadPluginDef(jar: File, parent: ClassLoader): IO[PluginError, Option[(PluginDef, PluginClassLoader)]] =
    for {
      pluginClassLoader <- UIO.delay(new PluginClassLoader(jar.toURI.toURL, parent))
      pluginDefClasses  <- UIO.delay(loadPluginDefClasses(pluginClassLoader))
      pluginDef         <- pluginDefClasses match {
                             case pluginDef :: Nil =>
                               IO.delay( // delayed because it can throw
                                 Some(pluginClassLoader.create[PluginDef](pluginDef, _ => ())())
                               ).redeemWith(
                                 {
                                   // raise class not found in the typed error channel as it may be recoverable
                                   case ex: ClassNotFoundException    => IO.raiseError(ClassNotFoundError(ex.getMessage))
                                   case ex: InvocationTargetException =>
                                     ex.getCause match {
                                       case ncdf: NoClassDefFoundError =>
                                         IO.raiseError(ClassNotFoundError("Could not find class: " + ncdf.getMessage))
                                       case _                          => IO.terminate(ex)
                                     }
                                   case other                         => IO.terminate(other)
                                 },
                                 value => IO.pure(value)
                               )
                             case Nil              =>
                               logger.warn(s"Jar file '$jar' does not contain a 'PluginDef' implementation.")
                               IO.none
                             case multiple         =>
                               IO.raiseError(MultiplePluginDefClassesFound(jar, multiple.toSet))

                           }
    } yield pluginDef.map(_ -> pluginClassLoader)

  private def loadPluginDefClasses(loader: ClassLoader)                                                              =
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
    * @param loaderConfig
    *   [[PluginsLoader]] configuration.
    * @return
    *   an instance of [[PluginsLoader]]
    */
  def apply(loaderConfig: PluginLoaderConfig): PluginsLoader = new PluginsLoader(loaderConfig)

  /**
    * [[PluginsLoader]] configuration.
    *
    * @param directories
    *   directories where to load [[Plugin]] s from.
    */
  final case class PluginLoaderConfig(directories: List[String])

  object PluginLoaderConfig {
    final def apply(directories: String*): PluginLoaderConfig =
      PluginLoaderConfig(directories.toList)
  }
}
