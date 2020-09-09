package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.error.AuthError
import ch.epfl.bluebrain.nexus.delta.sdk.model.{AuthToken, Caller}
import monix.bio.IO

/**
  * Operations pertaining to authentication, token validation and identities.
  */
trait Identities {

  /**
    * Attempt to exchange a token for a specific validated Caller.
    *
    * @param token a well formatted authentication token (usually a bearer token)
    */
  def exchange(token: AuthToken): IO[AuthError, Caller]

}
