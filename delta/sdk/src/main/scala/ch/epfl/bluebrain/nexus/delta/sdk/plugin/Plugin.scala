package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import monix.bio.Task

/**
  * Plugin API.
  */
trait Plugin {

  /**
    * Stop the plugin. This should allow the plugin to terminate gracefully.
    */
  def stop(): Task[Unit]
}
