package ch.epfl.bluebrain.nexus.delta.plugins.jira

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef

class JiraPluginDef extends PluginDef {

  override def module: ModuleDef = new JiraPluginModule(priority)

  override val info: PluginDescription = PluginDescription("jira", BuildInfo.version)

  override def initialize(locator: Locator): IO[Plugin] = IO.pure(JiraPlugin)

}
