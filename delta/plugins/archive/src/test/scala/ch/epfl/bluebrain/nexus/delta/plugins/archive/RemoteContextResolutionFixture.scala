package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf

trait RemoteContextResolutionFixture {
  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    contexts.archives              -> jsonContentOf("contexts/archives.json").topContextValueOrEmpty,
    Vocabulary.contexts.metadata   -> jsonContentOf("contexts/metadata.json").topContextValueOrEmpty,
    Vocabulary.contexts.error      -> jsonContentOf("contexts/error.json").topContextValueOrEmpty,
    Vocabulary.contexts.shacl      -> jsonContentOf("contexts/shacl.json").topContextValueOrEmpty,
    Vocabulary.contexts.statistics -> jsonContentOf("/contexts/statistics.json").topContextValueOrEmpty,
    Vocabulary.contexts.offset     -> jsonContentOf("/contexts/offset.json").topContextValueOrEmpty,
    Vocabulary.contexts.tags       -> jsonContentOf("contexts/tags.json").topContextValueOrEmpty,
    Vocabulary.contexts.search     -> jsonContentOf("contexts/search.json").topContextValueOrEmpty
  )
}
