package ch.epfl.bluebrain.nexus.delta.service.plugin

import java.io.{File, FileNotFoundException, FilenameFilter}

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef, Registry}
import io.github.classgraph.ClassGraph
import monix.bio.IO

import scala.jdk.CollectionConverters._

class PluginLoader(pluginConfig: PluginConfig) {

  private def loadPlugin(registry: Registry)(jar: File): IO[Throwable, Plugin] = {
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
        pluginClassLoader.create[PluginDef](pluginDef, println)().initialise(registry).mapError(e => e)
      case Nil              => IO.raiseError(new IllegalArgumentException("Needs at least one plugin class"))
      case multiple         =>
        IO.raiseError(new IllegalArgumentException(s"Too many plugin classes : ${multiple.mkString(",")}"))
    }
  }

  //TODO plugin initialization order based on dependency graph and checking dependencies
  def loadAndStartPlugins(registry: Registry): IO[Throwable, List[Plugin]] = {
    pluginConfig.pluginDir match {
      case None      => IO.apply(List.empty)
      case Some(dir) =>
        val pluginDir = new File(dir)
        if (!pluginDir.exists || !pluginDir.isDirectory) { IO.raiseError(new FileNotFoundException(dir)) }
        else {
          val pluginJars: Array[File] = pluginDir.listFiles(new FilenameFilter {
            override def accept(dir: File, name: String): Boolean = name.endsWith(".jar")
          })

          IO.sequence(pluginJars.map(loadPlugin(registry)))
        }
    }
  }

}

case class PluginConfig(pluginDir: Option[String])
