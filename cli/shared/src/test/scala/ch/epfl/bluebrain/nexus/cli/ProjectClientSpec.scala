package ch.epfl.bluebrain.nexus.cli

import java.util.UUID
import java.util.regex.Pattern.quote

import cats.effect.IO
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.cli.ProjectClient.{LiveProjectClient, TestProjectClient}
import ch.epfl.bluebrain.nexus.cli.error.ClientError.ClientStatusError
import ch.epfl.bluebrain.nexus.cli.types.Label
import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import org.http4s.Method._
import org.http4s._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ProjectClientSpec extends AnyWordSpecLike with Matchers with Fixtures {

  private val orgUuid      = UUID.randomUUID()
  private val orgLabel     = Label(genString())
  private val projectLabel = Label(genString())
  private val projectUuid  = UUID.randomUUID()

  "A LiveProjectClient" should {

    val cache = Ref[IO].of(Map.empty[(UUID, UUID), ProjectLabelRef]).unsafeRunSync()

    val replacements = Map(
      quote("{projectUuid}")  -> projectUuid.toString,
      quote("{orgUuid}")      -> orgUuid.toString,
      quote("{projectLabel}") -> projectLabel.toString,
      quote("{orgLabel}")     -> orgLabel.toString
    )

    val mockedHttpApp = HttpApp[IO] {
      case r
          if r.uri == endpoints.projectUri(orgUuid, projectUuid) &&
            r.method == GET &&
            r.headers.get(Authorization) == config.authorizationHeader =>
        IO.pure(Response[IO](Status.Ok).withEntity(jsonContentOf("/project.json", replacements)))

      case r
          if r.uri.toString().startsWith((config.endpoint / "projects").toString()) &&
            r.method == GET &&
            r.headers.get(Authorization) == config.authorizationHeader =>
        IO.pure(Response[IO](Status.NotFound).withEntity(jsonContentOf("/not_found.json")))

      case r if r.uri.toString().startsWith((config.endpoint / "projects").toString()) && r.method == GET =>
        IO.pure(Response[IO](Status.Forbidden).withEntity(jsonContentOf("/auth_failed.json")))
    }

    val mockedHttpClient: Client[IO] = Client.fromHttpApp(mockedHttpApp)

    val client: ProjectClient[IO] = new LiveProjectClient(mockedHttpClient, config, cache)

    "return a label" in {
      client.label(orgUuid, projectUuid).unsafeRunSync() shouldEqual Right(orgLabel -> projectLabel)
      cache.get.unsafeRunSync() shouldEqual Map((orgUuid, projectUuid)              -> ((orgLabel, projectLabel)))
    }

    "return a label from the cache" in {
      val emptyMockedClient = Client.fromHttpApp(HttpApp[IO] { _ => IO.raiseError(new RuntimeException("err")) })
      val cache             = Ref[IO].of(Map((orgUuid, projectUuid) -> ((orgLabel, projectLabel)))).unsafeRunSync()
      val client2           = ProjectClient[IO](emptyMockedClient, config, cache)
      client2.label(orgUuid, projectUuid).unsafeRunSync() shouldEqual Right(orgLabel -> projectLabel)
    }

    "return not found" in {
      val org     = UUID.randomUUID()
      val project = UUID.randomUUID()
      client.label(org, project).unsafeRunSync() shouldEqual
        Left(ClientStatusError(Status.NotFound, jsonContentOf("/not_found.json").noSpaces))
    }

    "return forbidden found" in {
      val client2: ProjectClient[IO] = ProjectClient[IO](mockedHttpClient, config.copy(token = None)).unsafeRunSync()
      client2.label(orgUuid, projectUuid).unsafeRunSync() shouldEqual
        Left(ClientStatusError(Status.Forbidden, jsonContentOf("/auth_failed.json").noSpaces))
    }
  }

  "A TestProjectClient" should {

    val cache                     = Map((orgUuid, projectUuid) -> ((orgLabel, projectLabel)))
    val client: ProjectClient[IO] = new TestProjectClient(cache)

    "return a label" in {
      client.label(orgUuid, projectUuid).unsafeRunSync() shouldEqual Right(orgLabel -> projectLabel)
    }

    "return not found" in {
      val org     = UUID.randomUUID()
      val project = UUID.randomUUID()
      client.label(org, project).unsafeRunSync() shouldEqual
        Left(ClientStatusError(Status.NotFound, "Project not found"))
    }
  }
}
