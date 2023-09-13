package ch.epfl.bluebrain.nexus.delta.sdk.auth

import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.{HttpRequest, Uri}
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.MigrateEffectSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.auth.Credentials.ClientCredentials
import ch.epfl.bluebrain.nexus.delta.sdk.error.AuthTokenError.{AuthTokenHttpError, AuthTokenNotFoundInResponse, RealmIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.ParsedToken
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.Json
import monix.bio.{IO, UIO}

class OpenIdAuthService(httpClient: HttpClient, realms: Realms) extends MigrateEffectSyntax {

  def auth(credentials: ClientCredentials): UIO[ParsedToken] = {
    for {
      realm       <- findRealm(credentials.realm)
      response    <- requestToken(realm.tokenEndpoint, credentials.user, credentials.password)
      parsedToken <- parseResponse(response)
    } yield {
      parsedToken
    }
  }

  private def findRealm(id: Label): UIO[Realm] = {
    for {
      realm <- realms.fetch(id).toUIO
      _     <- UIO.when(realm.deprecated)(UIO.terminate(RealmIsDeprecated(realm.value)))
    } yield realm.value
  }

  private def requestToken(tokenEndpoint: Uri, user: String, password: Secret[String]): UIO[Json] = {
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
      .hideErrorsWith(AuthTokenHttpError)
  }

  private def parseResponse(json: Json): UIO[ParsedToken] = {
    for {
      rawToken    <- json.hcursor.get[String]("access_token") match {
                       case Left(failure) => IO.terminate(AuthTokenNotFoundInResponse(failure))
                       case Right(value)  => UIO.pure(value)
                     }
      parsedToken <- IO.fromEither(ParsedToken.fromToken(AuthToken(rawToken))).hideErrors
    } yield {
      parsedToken
    }
  }
}
