package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task
class ArchivePluginDef extends PluginDef {

  override val module: ModuleDef = ArchivePluginModule

  override val info: PluginDescription = PluginDescription(Name.unsafe("archive"), BuildInfo.version)

  override def initialize(locator: Locator): Task[Plugin] = Task.pure(ArchivePlugin)
}
