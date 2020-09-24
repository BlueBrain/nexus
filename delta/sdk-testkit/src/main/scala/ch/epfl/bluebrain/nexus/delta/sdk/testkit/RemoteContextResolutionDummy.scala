package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution.Result
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextNotFound
import io.circe.Json
import monix.bio.IO
import org.apache.jena.iri.IRI

/**
  * Dummy implementation of [[RemoteContextResolution]] passing the expected results in a map
  */
final class RemoteContextResolutionDummy(contexts: Map[IRI, Json]) extends RemoteContextResolution {
  override def resolve(iri: IRI): Result[Json] =
    IO.fromEither(contexts.get(iri).toRight(RemoteContextNotFound(iri)))
}
