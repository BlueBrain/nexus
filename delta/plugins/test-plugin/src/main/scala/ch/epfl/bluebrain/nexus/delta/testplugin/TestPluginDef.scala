package ch.epfl.bluebrain.nexus.delta.testplugin

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef, PluginInfo}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

case class TestPluginDef() extends PluginDef {

  override def module: ModuleDef = new ModuleDef { make[TestPlugin] }

  override val info: PluginInfo = PluginInfo(Name.unsafe("testplugin"), "0.1.0")

  override def remoteContextResolution: RemoteContextResolution = RemoteContextResolution.never

  override def initialize(locator: Locator): Task[Plugin] = Task.pure(locator.get[TestPlugin])

}
