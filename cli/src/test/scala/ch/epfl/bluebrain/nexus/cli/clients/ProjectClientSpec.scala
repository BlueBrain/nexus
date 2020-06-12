package ch.epfl.bluebrain.nexus.cli.clients

import java.util.UUID

import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.{AbstractCliSpec, Console}
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.ClientStatusError
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, EnvConfig}
import ch.epfl.bluebrain.nexus.cli.sse._
import ch.epfl.bluebrain.nexus.cli.utils.Http4sExtras
import izumi.distage.model.definition.ModuleDef
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.{HttpApp, Response, Status}

class ProjectClientSpec extends AbstractCliSpec with Http4sExtras {

  private val projectJson = jsonContentOf("/templates/project.json", replacements)

  type Cache    = Map[(OrgUuid, ProjectUuid), (OrgLabel, ProjectLabel)]
  type CacheRef = Ref[IO, Cache]

  override def overrides: ModuleDef =
    new ModuleDef {
      include(defaultModules)
      make[Client[IO]].from { cfg: AppConfig =>
        val token   = cfg.env.token
        val httpApp = HttpApp[IO] {
          case GET -> `v1` / "projects" / OrgUuidVar(`orgUuid`) / ProjectUuidVar(`projectUuid`) optbearer `token` =>
            Response[IO](Status.Ok).withEntity(projectJson).pure[IO]
          case GET -> `v1` / "projects" / OrgUuidVar(_) / ProjectUuidVar(_) optbearer `token`                     =>
            Response[IO](Status.NotFound).withEntity(notFoundJson).pure[IO]
          case GET -> `v1` / "projects" / OrgUuidVar(_) / ProjectUuidVar(_) bearer (_: BearerToken)               =>
            Response[IO](Status.Forbidden).withEntity(authFailedJson).pure[IO]
        }
        Client.fromHttpApp(httpApp)
      }
      make[CacheRef].fromEffect {
        Ref.of[IO, Cache](Map.empty)
      }
    }

  "A ProjectClient" should {
    "resolve a known (orgUuid, projUuid) pair" in {
      (client: Client[IO], console: Console[IO], cache: CacheRef, env: EnvConfig) =>
        val cl = ProjectClient[IO](client, env, cache, console)
        for {
          labels <- cl.labels(orgUuid, projectUuid)
          _       = labels shouldEqual Right((orgLabel, projectLabel))
        } yield ()
    }
    "resolve from cache a known (orgUuid, projUuid) pair" in {
      (client: Client[IO], console: Console[IO], cache: CacheRef, env: EnvConfig) =>
        val errClient = Client.fromHttpApp(HttpApp[IO] { case GET -> Root => IO.pure(Response[IO](Status.NotFound)) })
        for {
          _      <- ProjectClient[IO](client, env, cache, console).labels(orgUuid, projectUuid)
          labels <- ProjectClient[IO](errClient, env, cache, console).labels(orgUuid, projectUuid)
          _       = labels shouldEqual Right((orgLabel, projectLabel))
        } yield ()
    }
    "fail to resolve an unknown (orgUuid, projUuid) pair" in {
      (client: Client[IO], console: Console[IO], cache: CacheRef, env: EnvConfig) =>
        val cl = ProjectClient[IO](client, env, cache, console)
        for {
          labels <- cl.labels(OrgUuid(UUID.randomUUID()), projectUuid)
          _       = labels shouldEqual Left(ClientStatusError(Status.NotFound, notFoundJson.noSpaces))
        } yield ()
    }
    "fail to resolve a known (orgUuid, projUuid) pair with bad credentials" in {
      (client: Client[IO], console: Console[IO], cache: CacheRef, env: EnvConfig) =>
        val cl = ProjectClient[IO](client, env.copy(token = Some(BearerToken("bad"))), cache, console)
        for {
          labels <- cl.labels(orgUuid, projectUuid)
          _       = labels shouldEqual Left(ClientStatusError(Status.Forbidden, authFailedJson.noSpaces))
        } yield ()
    }
  }
}
