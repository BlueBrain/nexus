package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import monix.bio.Task

trait PluginDef {

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
    * Initialize the plugin.
    *
    * @param registry dependencies registry
    *
    * @return [[Plugin]] instance.
    */
  def initialise(registry: Registry): Task[Plugin]
}
