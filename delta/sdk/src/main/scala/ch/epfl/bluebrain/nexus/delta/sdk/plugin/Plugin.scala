package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import akka.http.scaladsl.server.Route
import monix.bio.Task

/**
  * Plugin API.
  */
trait Plugin {

  /**
    * Optional routes provided by the plugin.
    */
  def route: Option[Route]

  /**
    * Stop the plugin. This should allow the plugin to terminate gracefully.
    */
  def stop(): Task[Unit]
}
