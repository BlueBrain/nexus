package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import org.http4s.AuthScheme
import org.http4s.Credentials.Token
import org.http4s.client.Client
import org.http4s.headers.Authorization

object TokenAuth {

  def apply(authTokenProvider: AuthTokenProvider, credentials: Credentials)(client: Client[IO]): Client[IO] =
    Client { request =>
      Resource.eval(authTokenProvider(credentials)).flatMap { authToken =>
        val requestWithAuth = authToken.fold(request) { token =>
          val authHeader = Authorization(Token(AuthScheme.Bearer, token.value))
          request.putHeaders(authHeader)
        }
        client.run(requestWithAuth)
      }
    }
}
