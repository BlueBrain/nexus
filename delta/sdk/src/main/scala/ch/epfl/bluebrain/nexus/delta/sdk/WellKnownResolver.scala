package ch.epfl.bluebrain.nexus.delta.sdk

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{RealmRejection, WellKnown}
import monix.bio.IO

/**
  * Well known configuration for an OIDC provider resolver.
  */
trait WellKnownResolver {

  /**
    * Resolves the passed well known ''uri''
    */
  def apply(uri: Uri): IO[RealmRejection, WellKnown]
}
