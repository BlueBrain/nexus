package ch.epfl.bluebrain.nexus.delta.sdk.auth

import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.{HttpRequest, Uri}
import cats.effect.IO
import cats.implicits.catsSyntaxMonadError
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.jwt.{AuthToken, ParsedToken}
import ch.epfl.bluebrain.nexus.delta.sdk.auth.Credentials.ClientCredentials
import ch.epfl.bluebrain.nexus.delta.sdk.error.AuthTokenError.{AuthTokenHttpError, AuthTokenNotFoundInResponse, RealmIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.Json

/**
  * Exchanges client credentials for an auth token with a remote OpenId service, as defined in the specified realm
  */
class OpenIdAuthService(httpClient: HttpClient, realms: Realms) {

  /**
    * Exchanges client credentials for an auth token with a remote OpenId service, as defined in the specified realm
    */
  def auth(credentials: ClientCredentials): IO[ParsedToken] = {
    for {
      realm       <- findRealm(credentials.realm)
      response    <- requestToken(realm.tokenEndpoint, credentials.user, credentials.password)
      parsedToken <- parseResponse(response)
    } yield {
      parsedToken
    }
  }

  private def findRealm(id: Label): IO[Realm] = {
    for {
      realm <- realms.fetch(id)
      _     <- IO.raiseWhen(realm.deprecated)(RealmIsDeprecated(realm.value))
    } yield realm.value
  }

  private def requestToken(tokenEndpoint: Uri, user: String, password: Secret[String]): IO[Json] = {
    httpClient
      .toJson(
        HttpRequest(
          method = POST,
          uri = tokenEndpoint,
          headers = Authorization(HttpCredentials.createBasicHttpCredentials(user, password.value)) :: Nil,
          entity = akka.http.scaladsl.model
            .FormData(
              Map(
                "scope"      -> "openid",
                "grant_type" -> "client_credentials"
              )
            )
            .toEntity
        )
      )
      .adaptError { case e: HttpClientError =>
        AuthTokenHttpError(e)
      }
  }

  private def parseResponse(json: Json): IO[ParsedToken] = {
    for {
      rawToken    <- json.hcursor.get[String]("access_token") match {
                       case Left(failure) => IO.raiseError(AuthTokenNotFoundInResponse(failure))
                       case Right(value)  => IO.pure(value)
                     }
      parsedToken <- IO.fromEither(ParsedToken.fromToken(AuthToken(rawToken)))
    } yield {
      parsedToken
    }
  }
}
