package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts => storageContexts}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.testkit.IOValues

trait RemoteContextResolutionFixture extends IOValues {
  implicit private val cl: ClassLoader = getClass.getClassLoader

  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    storageContexts.storages         -> ContextValue.fromFile("/contexts/storages.json").accepted,
    storageContexts.storagesMetadata -> ContextValue.fromFile("/contexts/storages-metadata.json").accepted,
    fileContexts.files               -> ContextValue.fromFile("/contexts/files.json").accepted,
    Vocabulary.contexts.metadata     -> ContextValue.fromFile("contexts/metadata.json").accepted,
    Vocabulary.contexts.error        -> ContextValue.fromFile("contexts/error.json").accepted,
    Vocabulary.contexts.tags         -> ContextValue.fromFile("contexts/tags.json").accepted,
    Vocabulary.contexts.search       -> ContextValue.fromFile("contexts/search.json").accepted
  )
}
