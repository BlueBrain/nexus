package ch.epfl.bluebrain.delta.testplugin

import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginInfo}
import monix.bio.Task

class TestPlugin(pluginDef: PluginInfo) extends Plugin {

  def initialize(): Task[Unit] = Task.delay(println("test"))

  /**
    * Optional routes provided by the plugin.
    */
  override def route: Option[Route] = None

  /**
    * Stop the plugin. This should allow the plugin to terminate gracefully.
    */
  override def stop(): Task[Unit] = Task.pure(println(s"Stopping: $pluginDef"))

  override def info: PluginInfo = pluginDef
}
