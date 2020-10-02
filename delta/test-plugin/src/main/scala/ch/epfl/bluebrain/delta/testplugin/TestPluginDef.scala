package ch.epfl.bluebrain.delta.testplugin

import ch.epfl.bluebrain.delta.testplugin.TestPluginDef.pluginInfo
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef, PluginInfo}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

case class TestPluginDef() extends PluginDef {

  /**
    * Initialize the plugin.
    *
    * @return [[Plugin]] instance.
    */
  override def initialise(locator: Locator): Task[Plugin] =
    Task.pure(locator.get[TestPlugin])

  /**
    * Plugin information
    */
  override def info: PluginInfo = pluginInfo

  override def module: ModuleDef =
    new ModuleDef {
      make[TestPlugin]
    }
}

case object TestPluginDef {
  val pluginInfo = PluginInfo(Name.unsafe("testplugin"), "0.1.0")
}
