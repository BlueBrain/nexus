package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import com.typesafe.config.Config
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

class CompositeViewsPluginDef extends PluginDef {

  override def module: Config => ModuleDef = new CompositeViewsPluginModule(priority, _)

  override val info: PluginDescription = PluginDescription(Name.unsafe("composite-views"), BuildInfo.version)

  override def initialize(locator: Locator): Task[Plugin] = Task.pure(CompositeViewsPlugin)

}
