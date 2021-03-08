package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts => storageContexts}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf

trait RemoteContextResolutionFixture {
  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    storageContexts.storages     -> jsonContentOf("/contexts/storages.json").topContextValueOrEmpty,
    fileContexts.files           -> jsonContentOf("/contexts/files.json").topContextValueOrEmpty,
    Vocabulary.contexts.metadata -> jsonContentOf("contexts/metadata.json").topContextValueOrEmpty,
    Vocabulary.contexts.error    -> jsonContentOf("contexts/error.json").topContextValueOrEmpty,
    Vocabulary.contexts.tags     -> jsonContentOf("contexts/tags.json").topContextValueOrEmpty,
    Vocabulary.contexts.search   -> jsonContentOf("contexts/search.json").topContextValueOrEmpty
  )
}
