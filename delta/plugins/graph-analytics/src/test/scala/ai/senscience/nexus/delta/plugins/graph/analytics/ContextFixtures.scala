package ai.senscience.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.testkit.scalatest.ClasspathResources

trait ContextFixtures extends ClasspathResources {
  implicit val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.relationships         -> jsonContentOf("contexts/relationships.json").topContextValueOrEmpty,
      contexts.properties            -> jsonContentOf("contexts/properties.json").topContextValueOrEmpty,
      Vocabulary.contexts.statistics -> jsonContentOf("contexts/statistics.json").topContextValueOrEmpty,
      Vocabulary.contexts.error      -> jsonContentOf("contexts/error.json").topContextValueOrEmpty
    )
}
