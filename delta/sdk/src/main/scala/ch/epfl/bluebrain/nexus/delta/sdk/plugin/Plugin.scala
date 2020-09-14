package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import akka.http.scaladsl.server.Route
import monix.bio.Task

/**
  * Plugin API.
  */
trait Plugin {

  /**
    * Start the plugin.
    *
    * @param deps  plugin dependencies
    * @return optional Akka Http [[Route]], if the plugin defines one.
    */
  def start(deps: PluginDependencies): Task[Option[Route]]

  /**
    * Stop the plugin. This should allow the plugin to terminate gracefully.
    */
  def stop(): Task[Unit]

  /**
    * Retrieve plugin status.
    *
    * @return current status of the plugin
    */
  def status(): Task[PluginStatus]

}
