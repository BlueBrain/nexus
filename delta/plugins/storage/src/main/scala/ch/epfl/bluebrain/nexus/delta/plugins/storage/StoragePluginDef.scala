package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef, PluginInfo}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

class StoragePluginDef extends PluginDef {

  /**
    * Distage module definition for this plugin.
    */
  override def module: ModuleDef = ???

  /**
    * Plugin information
    */
  override def info: PluginInfo = ???

  /**
    * Initialize the plugin.
    *
    * @param locator distage dependencies [[Locator]]
    * @return [[Plugin]] instance.
    */
  override def initialise(locator: Locator): Task[Plugin] = ???
}
