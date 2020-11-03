package ch.epfl.bluebrain.nexus.delta.sdk.model.realms

import io.circe.{Decoder, Encoder, Json}

/**
  * OAuth2 grant type enumeration.
  */
sealed trait GrantType extends Product with Serializable

object GrantType {

  /**
    * The Authorization Code grant type is used by confidential and public clients to exchange an authorization code for
    * an access token.
    *
    * @see https://tools.ietf.org/html/rfc6749#section-1.3.1
    */
  final case object AuthorizationCode extends GrantType

  /**
    * The Implicit grant type is a simplified flow that can be used by public clients, where the access token is
    * returned immediately without an extra authorization code exchange step.
    *
    * @see https://tools.ietf.org/html/rfc6749#section-1.3.2
    */
  final case object Implicit extends GrantType

  /**
    * The Password grant type is used by first-party clients to exchange a user's credentials for an access token.
    *
    * @see https://tools.ietf.org/html/rfc6749#section-1.3.3
    */
  final case object Password extends GrantType

  /**
    * The Client Credentials grant type is used by clients to obtain an access token outside of the context of a user.
    *
    * @see https://tools.ietf.org/html/rfc6749#section-1.3.4
    */
  final case object ClientCredentials extends GrantType

  /**
    * The Device Code grant type is used by browserless or input-constrained devices in the device flow to exchange a
    * previously obtained device code for an access token.
    *
    * @see https://tools.ietf.org/html/draft-ietf-oauth-device-flow-07#section-3.4
    */
  final case object DeviceCode extends GrantType

  /**
    * The Refresh Token grant type is used by clients to exchange a refresh token for an access token when the access
    * token has expired.
    *
    * @see https://tools.ietf.org/html/rfc6749#section-1.5
    */
  final case object RefreshToken extends GrantType

  object Snake {

    implicit final val grantTypeDecoder: Decoder[GrantType] = Decoder.decodeString.emap {
      case "authorization_code" => Right(AuthorizationCode)
      case "implicit"           => Right(Implicit)
      case "password"           => Right(Password)
      case "client_credentials" => Right(ClientCredentials)
      case "device_code"        => Right(DeviceCode)
      case "refresh_token"      => Right(RefreshToken)
      case other                => Left(s"Unknown grant type '$other'")
    }
  }

  object Camel {
    implicit final val grantTypeEncoder: Encoder[GrantType] = Encoder.instance {
      case AuthorizationCode => Json.fromString("authorizationCode")
      case Implicit          => Json.fromString("implicit")
      case Password          => Json.fromString("password")
      case ClientCredentials => Json.fromString("clientCredentials")
      case DeviceCode        => Json.fromString("deviceCode")
      case RefreshToken      => Json.fromString("refreshToken")
    }
    implicit final val grantTypeDecoder: Decoder[GrantType] = Decoder.decodeString.emap {
      case "authorizationCode" => Right(AuthorizationCode)
      case "implicit"          => Right(Implicit)
      case "password"          => Right(Password)
      case "clientCredentials" => Right(ClientCredentials)
      case "deviceCode"        => Right(DeviceCode)
      case "refreshToken"      => Right(RefreshToken)
      case other               => Left(s"Unknown grant type '$other'")
    }
  }
}
