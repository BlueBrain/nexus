package ch.epfl.bluebrain.nexus.delta.kernel.http.client.middleware

import cats.effect.IO
import org.http4s.BasicCredentials
import org.http4s.client.Client
import org.http4s.headers.Authorization

object BasicAuth {

  def apply(credentials: Option[BasicCredentials])(client: Client[IO]): Client[IO] = {
    val authorization = credentials.map(Authorization(_))
    Client { request =>
      val requestWithAuth = authorization.fold(request) { auth => request.putHeaders(auth) }
      client.run(requestWithAuth)
    }
  }
}
