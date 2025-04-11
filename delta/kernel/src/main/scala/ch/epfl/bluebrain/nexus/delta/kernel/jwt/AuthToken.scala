package ch.epfl.bluebrain.nexus.delta.kernel.jwt

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import io.circe.{Decoder, Encoder}

/**
  * Data type representing a authentication token, usually a OAuth2 bearer token.
  *
  * @param value
  *   the string representation of the token
  */
final case class AuthToken(value: String) extends AnyVal

object AuthToken {

  /**
    * Creates an AuthToken from a bearer token.
    *
    * @param bearer
    *   the bearer token
    */
  def apply(bearer: OAuth2BearerToken): AuthToken =
    new AuthToken(bearer.token)

  implicit val authTokenEncoder: Encoder[AuthToken] = Encoder.encodeString.contramap(_.value)
  implicit val authTokenDecoder: Decoder[AuthToken] = Decoder.decodeString.map(AuthToken(_))
}
