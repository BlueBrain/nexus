package ch.epfl.bluebrain.nexus.storage.config

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

/**
  * Configuration for IamClient identities endpoint.
  *
 * @param publicIri     base URL for all the identity IDs, excluding prefix.
  * @param internalIri   base URL for all the HTTP calls, excluding prefix.
  * @param prefix        the prefix
  */
final case class IamClientConfig(
    publicIri: AbsoluteIri,
    internalIri: AbsoluteIri,
    prefix: String
) {
  lazy val baseInternalIri            = internalIri + prefix
  lazy val basePublicIri              = publicIri + prefix
  lazy val identitiesIri: AbsoluteIri = baseInternalIri + "identities"
}
