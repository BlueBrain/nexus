package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import monix.bio.Task

trait PluginDef {

  /**
    * Plugin information
    */
  def info: PluginInfo

  /**
    * Plugin dependencies.
    */
  def dependencies: Set[PluginInfo]

  /**
    * Initialize the plugin.
    *
    * @param registry dependencies registry
    *
    * @return [[Plugin]] instance.
    */
  def initialise(registry: Registry): Task[Plugin]
}
