package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.contexts.files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts.storages
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef, PluginInfo}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

class StoragePluginDef extends PluginDef {

  private val classLoader = getClass.getClassLoader

  override def module: ModuleDef = PluginModule

  override val info: PluginInfo = PluginInfo(Name.unsafe("storage"), BuildInfo.version)

  override def remoteContextResolution: RemoteContextResolution =
    RemoteContextResolution.fixedIOResource(
      storages -> ioJsonContentOf("contexts/storages.json", classLoader).memoizeOnSuccess,
      files    -> ioJsonContentOf("contexts/files.json", classLoader).memoizeOnSuccess
    )

  override def priority: Int = 1

  override def initialize(locator: Locator): Task[Plugin] = Task.delay(locator.get[StoragePlugin])

}
