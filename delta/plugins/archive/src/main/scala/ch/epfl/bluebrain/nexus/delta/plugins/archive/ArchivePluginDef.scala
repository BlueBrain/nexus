package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginDef}
import izumi.distage.model.Locator
import izumi.distage.model.definition.ModuleDef
import monix.bio.Task

class ArchivePluginDef extends PluginDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  override val module: ModuleDef = ArchivePluginModule

  override val remoteContextResolution: RemoteContextResolution =
    RemoteContextResolution.fixedIOResource(
      contexts.archives -> ioJsonContentOf("contexts/archives.json").memoizeOnSuccess
    )

  override val info: PluginDescription = PluginDescription(Name.unsafe("archive"), BuildInfo.version)

  override def initialize(locator: Locator): Task[Plugin] = Task.delay(locator.get[ArchivePlugin])
}
