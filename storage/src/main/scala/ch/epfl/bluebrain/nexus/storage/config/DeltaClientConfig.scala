package ch.epfl.bluebrain.nexus.storage.config

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * Configuration for DeltaClient identities endpoint.
  *
  * @param publicIri     base URL for all the identity IDs, excluding prefix.
  * @param internalIri   base URL for all the HTTP calls, excluding prefix.
  * @param prefix        the prefix
  */
final case class DeltaClientConfig(
    publicIri: Iri,
    internalIri: Iri,
    prefix: String
) {
  lazy val baseInternalIri: Iri = internalIri / prefix
  lazy val basePublicIri: Iri   = publicIri / prefix
  lazy val identitiesIri: Iri   = baseInternalIri / "identities"
}
