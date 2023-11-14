package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts => storageContexts}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}

trait RemoteContextResolutionFixture {

  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixedIO(
    storageContexts.storages         -> ContextValue.fromFile("/contexts/storages.json"),
    storageContexts.storagesMetadata -> ContextValue.fromFile("/contexts/storages-metadata.json"),
    fileContexts.files               -> ContextValue.fromFile("/contexts/files.json"),
    Vocabulary.contexts.metadata     -> ContextValue.fromFile("contexts/metadata.json"),
    Vocabulary.contexts.error        -> ContextValue.fromFile("contexts/error.json"),
    Vocabulary.contexts.tags         -> ContextValue.fromFile("contexts/tags.json"),
    Vocabulary.contexts.search       -> ContextValue.fromFile("contexts/search.json")
  )
}
