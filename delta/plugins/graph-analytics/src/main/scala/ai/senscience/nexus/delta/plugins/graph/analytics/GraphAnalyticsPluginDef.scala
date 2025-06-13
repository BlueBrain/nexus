package ai.senscience.nexus.delta.plugins.graph.analytics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef

class GraphAnalyticsPluginDef extends PluginDef {

  override def module: ModuleDef = new GraphAnalyticsPluginModule(priority)

  override val info: PluginDescription = PluginDescription("graph-analytics", BuildInfo.version)

  override def initialize(locator: Locator): IO[Plugin] = IO.pure(GraphAnalyticsPlugin)

}
