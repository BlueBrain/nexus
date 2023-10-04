package ch.epfl.bluebrain.nexus.delta.sdk.identities

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{AuthToken, Caller}

/**
  * Operations pertaining to authentication, token validation and identities.
  */
trait Identities {

  /**
    * Attempt to exchange a token for a specific validated Caller.
    *
    * @param token
    *   a well formatted authentication token (usually a bearer token)
    */
  def exchange(token: AuthToken): IO[Caller]

}
