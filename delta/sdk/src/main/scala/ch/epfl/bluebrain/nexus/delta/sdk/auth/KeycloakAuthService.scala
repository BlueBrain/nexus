package ch.epfl.bluebrain.nexus.delta.sdk.auth

import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.model.headers.Authorization
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.toMonixtBIOOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sdk.error.TokenError.{ExpiryNotFoundInResponse, TokenHttpError, TokenNotFoundInResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.Json
import monix.bio.{IO, UIO}

import java.time.{Duration, Instant}

class KeycloakAuthService(httpClient: HttpClient, realms: Realms)(implicit clock: Clock[UIO]) {

  def auth(auth: AuthenticateAs): UIO[AccessTokenWithMetadata] = {
    for {
      realm                  <- realms.fetch(Label.unsafe(auth.realm)).toUIO
      response               <- requestToken(realm.value.tokenEndpoint, auth.user, auth.password)
      (token, validDuration) <- parseResponse(response)
      now                    <- IOUtils.instant
    } yield {
      AccessTokenWithMetadata(token, now.plus(validDuration))
    }
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
      .hideErrorsWith(TokenHttpError)
  }

  private def parseResponse(json: Json): UIO[(String, Duration)] = {
    for {
      token  <- json.hcursor.get[String]("access_token") match {
                  case Left(failure) => IO.terminate(TokenNotFoundInResponse(failure))
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
