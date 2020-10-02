package ch.epfl.bluebrain.delta.testplugin

import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef, PluginInfo, Registry}
import monix.bio.Task

case class TestPluginDef() extends PluginDef {

  /**
    * Plugin dependencies.
    */
  override def dependencies: Set[PluginInfo] = Set.empty

  /**
    * Initialize the plugin.
    *
   * @param registry dependencies registry
    * @return [[Plugin]] instance.
    */
  override def initialise(registry: Registry): Task[Plugin] =
    registry.lookup[Permissions].map(new TestPlugin(info, _))

  /**
    * Plugin information
    */
  override def info: PluginInfo = PluginInfo(Name.unsafe("testplugin"), "0.1.0")
}
