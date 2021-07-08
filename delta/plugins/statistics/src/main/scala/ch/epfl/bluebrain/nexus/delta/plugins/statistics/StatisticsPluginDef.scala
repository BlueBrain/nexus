package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

class StatisticsPluginDef extends PluginDef {

  override def module: ModuleDef = new StatisticsPluginModule(priority)

  override val info: PluginDescription = PluginDescription(Name.unsafe("statistics"), BuildInfo.version)

  override def initialize(locator: Locator): Task[Plugin] = Task.pure(StatisticsPlugin)

}
