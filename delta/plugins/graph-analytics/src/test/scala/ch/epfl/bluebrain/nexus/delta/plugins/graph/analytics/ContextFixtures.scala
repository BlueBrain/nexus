package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

trait ContextFixtures {
  implicit val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.relationships         -> jsonContentOf("contexts/relationships.json").topContextValueOrEmpty,
      contexts.properties            -> jsonContentOf("contexts/properties.json").topContextValueOrEmpty,
      Vocabulary.contexts.statistics -> jsonContentOf("contexts/statistics.json").topContextValueOrEmpty,
      Vocabulary.contexts.error      -> jsonContentOf("contexts/error.json").topContextValueOrEmpty
    )
}

object ContextFixtures extends ContextFixtures
