package ch.epfl.bluebrain.nexus.delta.sdk.utils

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf

trait Fixtures {

  implicit val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.shacl         -> jsonContentOf("contexts/shacl.json").topContextValueOrEmpty,
      contexts.error         -> jsonContentOf("contexts/error.json").topContextValueOrEmpty,
      contexts.metadata      -> jsonContentOf("contexts/metadata.json").topContextValueOrEmpty,
      contexts.permissions   -> jsonContentOf("contexts/permissions.json").topContextValueOrEmpty,
      contexts.organizations -> jsonContentOf("contexts/organizations.json").topContextValueOrEmpty,
      contexts.resolvers     -> jsonContentOf("contexts/resolvers.json").topContextValueOrEmpty
    )

}
