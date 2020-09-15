package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import monix.bio.Task

/**
  * Plugin API.
  */
trait Plugin {

  /**
    * Plugin name.
    */
  def name: Name

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
}
