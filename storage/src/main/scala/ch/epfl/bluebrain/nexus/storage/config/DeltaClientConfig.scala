package ch.epfl.bluebrain.nexus.storage.config

import org.apache.jena.iri.IRI
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._

/**
  * Configuration for DeltaClient identities endpoint.
  *
  * @param publicIri     base URL for all the identity IDs, excluding prefix.
  * @param internalIri   base URL for all the HTTP calls, excluding prefix.
  * @param prefix        the prefix
  */
final case class DeltaClientConfig(
    publicIri: IRI,
    internalIri: IRI,
    prefix: String
) {
  lazy val baseInternalIri: IRI = internalIri / prefix
  lazy val basePublicIri: IRI   = publicIri / prefix
  lazy val identitiesIri: IRI   = baseInternalIri / "identities"
}
