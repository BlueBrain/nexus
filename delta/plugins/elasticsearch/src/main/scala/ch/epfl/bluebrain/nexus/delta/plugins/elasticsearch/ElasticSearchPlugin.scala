package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginInfo}
import monix.bio.Task

class ElasticSearchPlugin extends Plugin {
  /**
    * Plugin information
    */
  override def info: PluginInfo = ???

  /**
    * Optional routes provided by the plugin.
    */
  override def route: Option[Route] = ???

  /**
    * Stop the plugin. This should allow the plugin to terminate gracefully.
    */
  override def stop(): Task[Unit] = ???
}
