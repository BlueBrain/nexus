package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import cats.effect.IO

/**
  * Plugin API.
  */
trait Plugin {

  /**
    * Stop the plugin. This should allow the plugin to terminate gracefully.
    */
  def stop(): IO[Unit]
}
