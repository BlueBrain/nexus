package ch.epfl.bluebrain.nexus.delta.service.plugin

import java.io.{File, FilenameFilter}

import ch.epfl.bluebrain.nexus.delta.sdk.error.PluginError.{DependencyGraphCycle, DependencyNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef, PluginInfo, Registry}
import io.github.classgraph.ClassGraph
import monix.bio.IO
import scalax.collection.Graph
import scalax.collection.GraphEdge.DiEdge

import scala.jdk.CollectionConverters._

class PluginLoader(pluginConfig: PluginConfig) {

  private def loadPluginDef(jar: File): IO[Throwable, PluginDef] = {
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
      case Nil              => IO.raiseError(new IllegalArgumentException("Needs at least one plugin class"))
      case multiple         =>
        IO.raiseError(new IllegalArgumentException(s"Too many plugin classes : ${multiple.mkString(",")}"))

    }
  }

  private def buildPluginDependencyGraph(pluginDeps: Map[PluginInfo, Set[PluginInfo]]): Graph[PluginInfo, DiEdge] =
    Graph.from(pluginDeps.keys, pluginDeps.flatMap { case (plugin, deps) => deps.map(DiEdge(plugin, _)) })

  //TODO refactor this a bit
  def loadAndStartPlugins(registry: Registry): IO[Throwable, List[Plugin]] = {

    val pluginJars = pluginConfig.pluginDir match {
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
      pluginsDefs                        <- IO.sequence(pluginJars.map(loadPluginDef))
      pluginDeps                          = pluginsDefs.map(pDef => pDef.info -> pDef.dependencies).toMap
      _                                  <- IO.sequence(pluginDeps.map {
                                              case (pDef, deps) =>
                                                val unmet = deps.filterNot(pluginDeps.isDefinedAt)
                                                if (unmet.isEmpty)
                                                  IO.unit
                                                else
                                                  IO.raiseError(DependencyNotFound(pDef, unmet))
                                            })
      depGraph: Graph[PluginInfo, DiEdge] = buildPluginDependencyGraph(pluginDeps)
      pluginInitOrder                    <-
        depGraph
          .topologicalSort[PluginDef]
          .fold(_ => IO.raiseError(DependencyGraphCycle(depGraph.toString())), order => IO.pure(order.toOuter))
      plugins                            <-
        IO.sequence(
          pluginInitOrder.map(pInfo =>
            pluginsDefs.find(_.info == pInfo).getOrElse(throw new IllegalArgumentException()).initialise(registry)
          )
        )
    } yield plugins

  }

}

case class PluginConfig(pluginDir: Option[String])
