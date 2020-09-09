package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.http.scaladsl.model.headers.OAuth2BearerToken

/**
  * Data type representing a authentication token, usually a OAuth2 bearer token.
  *
 * @param value the string representation of the token
  */
final case class AuthToken private (value: String)

object AuthToken {

  /**
    * Creates an AuthToken from a bearer token.
    *
   * @param bearer the bearer token
    */
  def apply(bearer: OAuth2BearerToken): AuthToken =
    new AuthToken(bearer.token)

  /**
    * Creates an AuthToken from a string representation. No validations are performed in terms of format and consistency.
    *
    * @param value the string representation of the token
    */
  def unsafe(value: String): AuthToken =
    new AuthToken(value)
}
