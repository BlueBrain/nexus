package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts => storageContexts}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}

trait RemoteContextResolutionFixture {
  implicit private val cl: ClassLoader = getClass.getClassLoader

  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixedIO(
    storageContexts.storages         -> ContextValue.fromFile("contexts/storages.json"),
    storageContexts.storagesMetadata -> ContextValue.fromFile("contexts/storages-metadata.json"),
    fileContexts.files               -> ContextValue.fromFile("contexts/files.json"),
    contexts.archives                -> ContextValue.fromFile("contexts/archives.json"),
    contexts.archivesMetadata        -> ContextValue.fromFile("contexts/archives-metadata.json"),
    Vocabulary.contexts.metadata     -> ContextValue.fromFile("contexts/metadata.json"),
    Vocabulary.contexts.error        -> ContextValue.fromFile("contexts/error.json"),
    Vocabulary.contexts.tags         -> ContextValue.fromFile("contexts/tags.json")
  )
}

object RemoteContextResolutionFixture extends RemoteContextResolutionFixture
