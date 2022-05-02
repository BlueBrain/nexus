package ch.epfl.bluebrain.nexus.delta.plugins.jira

import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import scala.annotation.nowarn

/**
  * OAuth token provided by the Jira instance
  *
  * In Jira, a client is authenticated as the user involved in the OAuth dance and is authorized to have read and write
  * access as that user. The data that can be retrieved and changed by the client is controlled by the user's
  * permissions in Jira. The authorization process works by getting the resource owner to grant access to their
  * information on the resource by authorizing a request token. This request token is used by the consumer to obtain an
  * access token from the resource. Once the client has an access token, it can use the access token to make
  * authenticated requests to the resource until the token expires or is revoked.
  *
  * This process can be separated into three stages:
  *   - The user authorizes the client with Jira to receive an access code.
  *   - The client makes a request to Jira with the access code and receives an access token.
  *   - The client can now receive data from Jira when it makes a request including the access token. Note, the client
  *     can continue making authenticated requests to Jira until the token expires or is revoked.
  *
  * @see
  *   https://developer.atlassian.com/server/jira/platform/oauth/
  */
sealed trait OAuthToken extends Product with Serializable {
  def value: String
}

object OAuthToken {

  /**
    * Token issued during the authorization request
    */
  final case class RequestToken(value: String) extends OAuthToken

  /**
    * Access token to query Jira
    */
  final case class AccessToken(value: String) extends OAuthToken

  @nowarn("cat=unused")
  implicit val oauthTokenEncoder: Codec.AsObject[OAuthToken] = {
    implicit val cfg: Configuration = Configuration.default
    deriveConfiguredCodec[OAuthToken]
  }
}
