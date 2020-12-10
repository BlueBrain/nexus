package ch.epfl.bluebrain.nexus.delta.plugins.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.testkit.TestHelpers

trait RemoteContextResolutionFixture extends TestHelpers {
  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata -> jsonContentOf("/contexts/metadata.json"),
    contexts.storage             -> jsonContentOf("/contexts/storages.json")
  )
}
