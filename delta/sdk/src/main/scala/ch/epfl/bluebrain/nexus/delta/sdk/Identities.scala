package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, TokenRejection}
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
  def exchange(token: AuthToken): IO[TokenRejection, Caller]

}
