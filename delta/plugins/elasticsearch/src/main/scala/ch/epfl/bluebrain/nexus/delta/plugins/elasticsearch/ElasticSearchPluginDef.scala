package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef

class ElasticSearchPluginDef extends PluginDef {

  override def module: ModuleDef = new ElasticSearchPluginModule(priority)

  override val info: PluginDescription = PluginDescription(Name.unsafe("elasticsearch"), BuildInfo.version)

  override def initialize(locator: Locator): IO[Plugin] = IO.pure(ElasticSearchPlugin)
}
