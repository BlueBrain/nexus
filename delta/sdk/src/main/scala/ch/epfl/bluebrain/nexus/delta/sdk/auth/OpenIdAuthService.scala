package ch.epfl.bluebrain.nexus.delta.sdk.auth

import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.{HttpRequest, Uri}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.MigrateEffectSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sdk.auth.Credentials.ClientCredentials
import ch.epfl.bluebrain.nexus.delta.sdk.error.AuthTokenError.{AuthTokenHttpError, AuthTokenNotFoundInResponse, ExpiryNotFoundInResponse, RealmIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.Json
import monix.bio.{IO, UIO}

import java.time.{Duration, Instant}

class OpenIdAuthService(httpClient: HttpClient, realms: Realms)(implicit clock: Clock[UIO])
    extends MigrateEffectSyntax {

  def auth(credentials: ClientCredentials): UIO[AccessTokenWithMetadata] = {
    for {
      realm                  <- findRealm(credentials.realm)
      response               <- requestToken(realm.tokenEndpoint, credentials.user, credentials.password)
      (token, validDuration) <- parseResponse(response)
      now                    <- IOUtils.instant
    } yield {
      AccessTokenWithMetadata(token, now.plus(validDuration))
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

  private def parseResponse(json: Json): UIO[(String, Duration)] = {
    for {
      token  <- json.hcursor.get[String]("access_token") match {
                  case Left(failure) => IO.terminate(AuthTokenNotFoundInResponse(failure))
                  case Right(value)  => UIO.pure(value)
                }
      expiry <- json.hcursor.get[Long]("expires_in") match {
                  case Left(failure) => IO.terminate(ExpiryNotFoundInResponse(failure))
                  case Right(value)  => UIO.pure(value)
                }
    } yield {
      (token, Duration.ofSeconds(expiry))
    }
  }
}

case class AccessTokenWithMetadata(token: String, expiresAt: Instant)
