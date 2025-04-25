package ch.epfl.bluebrain.nexus.delta.kernel.http.client.middleware

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.Method.GET
import org.http4s.Uri.Path.Root
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.dsl.io.{/, *}
import org.http4s.server.middleware.authentication.BasicAuth as ServerBasicAuth
import org.http4s.syntax.all.*
import org.http4s.{HttpApp, *}

class BasicAuthSuite extends CatsEffectSuite {

  private val validCredentials = BasicCredentials("username", "password")

  private val realm = "test"

  private val authStore = (incomingCredentials: BasicCredentials) =>
    if (incomingCredentials == validCredentials) IO.some(incomingCredentials.username)
    else IO.none

  private val basicAuth = ServerBasicAuth(realm, authStore)

  private val protectedUri = uri"/protected"

  private val service =
    AuthedRoutes.of[String, IO] {
      case GET -> Root / "protected" as user =>
        Ok(s"Logged in as $user")
      case _                                 => NotFound()
    }

  private val authService: HttpApp[IO] = basicAuth(service).orNotFound

  private val client = Client.fromHttpApp[IO](authService)

  private def requestProtected(client: Client[IO]) =
    client.status(GET(protectedUri))

  test("Client without basic auth should fail with 401") {
    val authClient = BasicAuth(None)(client)

    requestProtected(client).assertEquals(Status.Unauthorized) >>
      requestProtected(authClient).assertEquals(Status.Unauthorized)
  }

  test("Client with invalid basic auth should fail with 401") {
    val wrongCredentials = BasicCredentials("xxx", "xxx")
    val authClient       = BasicAuth(Some(wrongCredentials))(client)

    requestProtected(authClient).assertEquals(Status.Unauthorized)
  }

  test("Auth client with correct basic auth should succeed") {
    val authClient = BasicAuth(Some(validCredentials))(client)
    authClient.status(GET(protectedUri)).assertEquals(Status.Ok)
  }
}
