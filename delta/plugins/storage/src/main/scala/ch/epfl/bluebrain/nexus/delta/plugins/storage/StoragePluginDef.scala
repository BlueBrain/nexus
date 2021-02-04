package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.contexts.files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts.storages
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

class StoragePluginDef extends PluginDef {

  implicit private val classLoader = getClass.getClassLoader

  override def module: ModuleDef = StoragePluginModule

  override val info: PluginDescription = PluginDescription(Name.unsafe("storage"), BuildInfo.version)

  override val remoteContextResolution: RemoteContextResolution =
    RemoteContextResolution.fixedIOResource(
      storages -> ioJsonContentOf("contexts/storages.json").memoizeOnSuccess,
      files    -> ioJsonContentOf("contexts/files.json").memoizeOnSuccess
    )

  override def initialize(locator: Locator): Task[Plugin] = Task.delay(locator.get[StoragePlugin])

}
