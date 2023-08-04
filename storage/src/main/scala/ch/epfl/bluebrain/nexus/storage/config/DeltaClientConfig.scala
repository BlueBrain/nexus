package ch.epfl.bluebrain.nexus.storage.config

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.storage.UriUtils.addPath

/**
  * Configuration for DeltaClient identities endpoint.
  *
  * @param publicIri
  *   base URL for all the identity IDs, excluding prefix.
  * @param internalIri
  *   base URL for all the HTTP calls, excluding prefix.
  * @param prefix
  *   the prefix
  */
final case class DeltaClientConfig(
    publicIri: Uri,
    internalIri: Uri,
    prefix: String
) {
  lazy val baseInternalIri: Uri = addPath(internalIri, prefix)
  lazy val basePublicIri: Uri   = addPath(publicIri, prefix)
  lazy val identitiesIri: Uri   = addPath(baseInternalIri, "identities")
}
