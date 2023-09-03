package ch.epfl.bluebrain.nexus.delta.sdk.auth

import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.{HttpRequest, Uri}
import akka.http.scaladsl.model.headers.Authorization
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.MigrateEffectSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.error.TokenError.{TokenHttpError, TokenNotFoundInResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.Json
import monix.bio.{IO, UIO}

/**
  * Provides an auth token for the service account, for use when comunicating with remote storage
  */
trait AuthTokenProvider {
  def apply(): UIO[Option[AuthToken]]
}

object AuthTokenProvider {
  def apply(auth: Option[AuthenticateAs], httpClient: HttpClient, realms: Realms): AuthTokenProvider = {
    auth match {
      case Some(authAs) => new KeycloakAuthTokenProvider(authAs, httpClient, realms)
      case None         => new AnonymousAuthTokenProvider
    }
  }
  def anonymousForTest: AuthTokenProvider = new AnonymousAuthTokenProvider
}

private class AnonymousAuthTokenProvider extends AuthTokenProvider {
  override def apply(): UIO[Option[AuthToken]] = UIO.pure(None)
}

private class KeycloakAuthTokenProvider(auth: AuthenticateAs, httpClient: HttpClient, realms: Realms)
    extends AuthTokenProvider
    with MigrateEffectSyntax {
  override def apply(): UIO[Option[AuthToken]] = {
    for {
      realm       <- realms.fetch(Label.unsafe(auth.realm)).toUIO
      accessToken <- requestAccessToken(realm, auth.user, auth.password)
    } yield Some(AuthToken(accessToken))
  }

  private def requestAccessToken(realm: RealmResource, user: String, password: Secret[String]): UIO[String] = {
    requestToken(realm.value.tokenEndpoint, user, password)
      .flatMap(parseTokenFromResponse)
  }

  private def requestToken(tokenEndpoint: Uri, user: String, password: Secret[String]) = {
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

  private def parseTokenFromResponse(json: Json): UIO[String] = {
    json.hcursor.get[String]("access_token") match {
      case Left(failure) => IO.terminate(TokenNotFoundInResponse(failure))
      case Right(value)  => UIO.pure(value)
    }
  }
}
