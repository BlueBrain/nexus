package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts => storageContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.testkit.TestHelpers

trait RemoteContextResolutionFixture extends TestHelpers {
  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata -> jsonContentOf("/contexts/metadata.json"),
    storageContexts.storages     -> jsonContentOf("/contexts/storages.json"),
    fileContexts.files           -> jsonContentOf("/contexts/files.json")
  )
}
