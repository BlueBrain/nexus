package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.{contexts, schema => archivesSchemaId}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name, ResourceToSchemaMappings}
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

  override val resourcesToSchemas: ResourceToSchemaMappings =
    ResourceToSchemaMappings(Label.unsafe("archives") -> archivesSchemaId.iri)

  override val apiMappings: ApiMappings                     = Archives.mappings

  override def initialize(locator: Locator): Task[Plugin] = Task.delay(locator.get[ArchivePlugin])
}
