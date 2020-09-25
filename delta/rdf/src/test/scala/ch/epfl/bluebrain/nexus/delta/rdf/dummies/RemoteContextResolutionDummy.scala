package ch.epfl.bluebrain.nexus.delta.rdf.dummies

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution.Result
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextNotFound
import io.circe.Json
import monix.bio.IO

/**
  * Dummy implementation of [[RemoteContextResolution]] passing the expected results in a map
  */
final class RemoteContextResolutionDummy(contexts: Map[Iri, Json]) extends RemoteContextResolution {
  override def resolve(iri: Iri): Result[Json] =
    IO.fromEither(contexts.get(iri).toRight(RemoteContextNotFound(iri)))
}

object RemoteContextResolutionDummy {

  /**
    * Helper method to construct a [[RemoteContextResolutionDummy]]
    */
  def apply(tuple: (Iri, Json)*): RemoteContextResolutionDummy =
    new RemoteContextResolutionDummy(Map.from(tuple))
}
