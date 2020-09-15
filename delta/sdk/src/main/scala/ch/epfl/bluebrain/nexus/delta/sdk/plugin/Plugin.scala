package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import akka.http.scaladsl.server.Route
import monix.bio.Task

/**
  * Plugin API.
  */
trait Plugin {

  /**
    * Plugin name.
    */
  def name: String

  /**
    * Plugin version.
    */
  def version: String

  /**
    * Plugin dependencies.
    */
  def dependencies: Set[PluginDef]

  /**
    * Optional routes provided by the plugin.
    */
  def route: Option[Route]

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
