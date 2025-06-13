package ai.senscience.nexus.delta.plugin

import java.net.URL
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

/**
  * A mutable classloader that aggregates the plugins class loaders.
  */
class PluginsClassLoader(pluginsClassLoader: List[PluginClassLoader], parent: ClassLoader) extends ClassLoader(parent) {

  private val cls = ListBuffer.from(pluginsClassLoader)

  private[plugin] def addPluginClassLoader(classLoader: PluginClassLoader): Unit =
    cls.addOne(classLoader)

  /**
    * Loads the class with the specified class name.
    *
    * It first tries to find the class in the parent [[ClassLoader]] and then it attempts to find it in the plugins.
    * scala classes are always loaded by the parent [[ClassLoader]].
    *
    * @param className
    *   The binary name of the class
    * @return
    *   The resulting [[Class]] object
    */
  override def loadClass(className: String): Class[?] =
    loadClassFromParent(className)
      .orElse(loadClassFromPlugins(className, cls.toList))
      .getOrElse(throw new ClassNotFoundException(className))

  /**
    * Finds the resource with the given name.
    *
    * Returns the resource from the parent classpath if exists Otherwise, it attempts to find it in the plugin's
    * classpath
    *
    * @param name
    *   the name of the resource.
    * @return
    *   the URL to the resource, null if the resource was not found.
    */
  override def getResource(name: String): URL =
    getResourceFromParent(name).orElse(getResourceFromPlugins(name, cls.toList)).orNull

  private def loadClassFromParent(className: String): Option[Class[?]] =
    Try(super.loadClass(className)) match {
      case Success(result)                    => Some(result)
      case Failure(_: ClassNotFoundException) => None
      case Failure(ex)                        => throw ex
    }

  @tailrec
  private def loadClassFromPlugins(
      className: String,
      rest: List[PluginClassLoader]
  ): Option[Class[?]] =
    rest match {
      case head :: tail =>
        head.loadClassFromPlugin(className) match {
          case None  => loadClassFromPlugins(className, tail)
          case other => other
        }
      case Nil          => None
    }

  @tailrec
  private def getResourceFromPlugins(
      name: String,
      rest: List[PluginClassLoader]
  ): Option[URL] =
    rest match {
      case head :: tail =>
        head.getResourceFromPlugin(name) match {
          case None  => getResourceFromPlugins(name, tail)
          case other => other
        }
      case Nil          => None
    }

  private def getResourceFromParent(name: String): Option[URL] =
    Option(super.getResource(name))

}
