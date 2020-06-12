package ch.epfl.bluebrain.nexus.cli.clients

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.{AbstractCliSpec, Console}
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.{ClientStatusError, ServerStatusError}
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, EnvConfig}
import ch.epfl.bluebrain.nexus.cli.sse._
import ch.epfl.bluebrain.nexus.cli.utils.Http4sExtras
import izumi.distage.model.definition.ModuleDef
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.http4s.{HttpApp, Response, Status, Uri}
import org.scalatest.OptionValues

class SparqlClientSpec extends AbstractCliSpec with Http4sExtras with OptionValues {

  private val sparqlResultsJson = jsonContentOf("/templates/sparql-results.json")
  private val sparqlResults     = sparqlResultsJson.as[SparqlResults].toOption.value
  private val query             = "SELECT * {?s ?p ?o} LIMIT 10"

  override def overrides: ModuleDef =
    new ModuleDef {
      include(defaultModules)
      make[Client[IO]].from { cfg: AppConfig =>
        val token   = cfg.env.token
        val ct      = `Content-Type`(SparqlClient.`application/sparql-query`)
        val view    = cfg.env.defaultSparqlView.renderString
        val httpApp = HttpApp[IO] {
          // success
          case req @ POST -> `v1` / "views" / OrgLabelVar(`orgLabel`) / ProjectLabelVar(
                `projectLabel`
              ) / `view` / "sparql" contentType `ct` optbearer `token` =>
            req.as[String].flatMap {
              case `query` => Response[IO](Status.Ok).withEntity(sparqlResultsJson).pure[IO]
              case _       => Response[IO](Status.BadRequest).pure[IO]
            }
          // unknown view id
          case req @ POST -> `v1` / "views" / OrgLabelVar(`orgLabel`) / ProjectLabelVar(
                `projectLabel`
              ) / (_: String) / "sparql" contentType `ct` optbearer `token` =>
            req.as[String].flatMap {
              case `query` => Response[IO](Status.NotFound).withEntity(notFoundJson).pure[IO]
              case _       => Response[IO](Status.BadRequest).pure[IO]
            }
          // unknown token
          case req @ POST -> `v1` / "views" / OrgLabelVar(`orgLabel`) / ProjectLabelVar(
                `projectLabel`
              ) / `view` / "sparql" contentType `ct` optbearer (_: Option[
                BearerToken
              ]) =>
            req.as[String].flatMap {
              case `query` => Response[IO](Status.Forbidden).withEntity(authFailedJson).pure[IO]
              case _       => Response[IO](Status.BadRequest).pure[IO]
            }
          // other - internal error
          case req @ POST -> "v1" /: (_: Path) contentType `ct` optbearer `token` =>
            req.as[String].flatMap {
              case `query` => Response[IO](Status.InternalServerError).withEntity(internalErrorJson).pure[IO]
              case _       => Response[IO](Status.BadRequest).pure[IO]
            }
        }
        Client.fromHttpApp(httpApp)
      }
    }

  "A SparqlClient" should {
    "return sparql results" in { (client: Client[IO], console: Console[IO], env: EnvConfig) =>
      val cl = SparqlClient(client, env, console)
      for {
        results <- cl.query(orgLabel, projectLabel, query)
        _        = results shouldEqual Right(sparqlResults)
      } yield ()
    }
    "return not found" in { (client: Client[IO], console: Console[IO], env: EnvConfig) =>
      val cl = SparqlClient(client, env, console)
      for {
        results <- cl.query(orgLabel, projectLabel, Uri.unsafeFromString(genString()), query)
        _        = results shouldEqual Left(ClientStatusError(Status.NotFound, notFoundJson.noSpaces))
      } yield ()
    }
    "return internal error" in { (client: Client[IO], console: Console[IO], env: EnvConfig) =>
      val cl = SparqlClient(client, env, console)
      for {
        results <- cl.query(orgLabel, ProjectLabel(genString()), Uri.unsafeFromString(genString()), query)
        _        = results shouldEqual Left(ServerStatusError(Status.InternalServerError, internalErrorJson.noSpaces))
      } yield ()
    }
    "return bad token" in { (client: Client[IO], console: Console[IO], env: EnvConfig) =>
      val cl = SparqlClient(client, env.copy(token = Some(BearerToken("bad"))), console)
      for {
        results <- cl.query(orgLabel, projectLabel, query)
        _        = results shouldEqual Left(ClientStatusError(Status.Forbidden, authFailedJson.noSpaces))
      } yield ()
    }

  }
}
