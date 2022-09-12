package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.Indexing
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

class ElasticSearchPluginDef extends PluginDef {

  override def module: ModuleDef = new ElasticSearchPluginModule(priority)

  override val info: PluginDescription = PluginDescription(Name.unsafe("elasticsearch"), BuildInfo.version)

  override def initialize(locator: Locator): Task[Plugin] =
    Indexing.startIndexing(locator.get[ReferenceRegistry], locator.get[Supervisor]).as(ElasticSearchPlugin)
}
