package ch.epfl.bluebrain.nexus.delta.sdk.plugin

import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

trait PluginDef {

  /**
    * Distage module definition for this plugin.
    */
  def module: ModuleDef

  /**
    * Plugin information
    */
  def info: PluginInfo

  /**
    * Initialize the plugin.
    *
    * @param locator  distage dependencies locator
    *
    * @return [[Plugin]] instance.
    */
  def initialise(locator: Locator): Task[Plugin]
}
