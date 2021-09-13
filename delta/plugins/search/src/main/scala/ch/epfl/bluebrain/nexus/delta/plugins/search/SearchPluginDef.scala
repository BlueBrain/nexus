package ch.epfl.bluebrain.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

class SearchPluginDef extends PluginDef {

  override def module: ModuleDef = new SearchPluginModule(priority)

  override val info: PluginDescription = PluginDescription(Name.unsafe("search"), BuildInfo.version)

  override def initialize(locator: Locator): Task[Plugin] = Task.pure(SearchPlugin)

}
