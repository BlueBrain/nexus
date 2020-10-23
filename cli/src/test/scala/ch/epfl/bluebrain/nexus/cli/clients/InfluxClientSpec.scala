package ch.epfl.bluebrain.nexus.cli.clients

import java.time.Instant

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.ClientStatusError
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.dummies.TestConsole
import ch.epfl.bluebrain.nexus.cli.utils.{Http4sExtras, TimeTransformation}
import ch.epfl.bluebrain.nexus.cli.AbstractCliSpec
import izumi.distage.model.definition.ModuleDef
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.{HttpApp, Response, Status, UrlForm}

class InfluxClientSpec extends AbstractCliSpec with Http4sExtras with TimeTransformation {

  private val created      = Instant.now()
  private val updated      = created.plusSeconds(5)
  private val allowedPoint =
    s"m1,created=${created.toString},project=org/proj,deprecated=false bytes=1234 ${toNano(updated)}"

  private val allowedQuery        = """SELECT * FROM "m1""""
  private val influxQlResultsJson = jsonContentOf("/templates/influxql-results.json")

  override def overrides: ModuleDef =
    new ModuleDef {
      include(defaultModules)
      make[Client[IO]].from { cfg: AppConfig =>
        val httpApp = HttpApp[IO] {
          case req @ POST -> Root / "query" db cfg.influx.database =>
            req.as[UrlForm].flatMap {
              case urlForm if urlForm.get("q").toList.mkString("") == allowedQuery =>
                Response[IO](Status.Ok).withEntity(influxQlResultsJson).pure[IO]
              case _                                                               =>
                Response[IO](Status.BadRequest).withEntity("some create db error").pure[IO]
            }
          case req @ POST -> Root / "query"                        =>
            req.as[UrlForm].flatMap {
              case urlForm if urlForm.get("q").toList.mkString("") == cfg.influx.dbCreationCommand =>
                Response[IO](Status.Ok).pure[IO]
              case _                                                                               =>
                Response[IO](Status.BadRequest).withEntity("some create db error").pure[IO]
            }
          case req @ POST -> Root / "write" db cfg.influx.database =>
            req.as[String].flatMap {
              case `allowedPoint` => Response[IO](Status.Ok).pure[IO]
              case _              => Response[IO](Status.BadRequest).withEntity("some write error").pure[IO]
            }
          case _                                                   =>
            Response[IO](Status.BadRequest).withEntity("some other error").pure[IO]
        }
        Client.fromHttpApp(httpApp)
      }
    }

  "An InfluxClient" should {

    "create a db" in { (client: Client[IO], console: TestConsole[IO], cfg: AppConfig) =>
      val cl = InfluxClient[IO](client, cfg, console)
      for {
        dbResult <- cl.createDb
        err      <- console.errQueue.tryDequeue1
        _         = err shouldEqual None
        _         = dbResult shouldEqual ()
      } yield ()
    }

    "write a point" in { (client: Client[IO], console: TestConsole[IO], cfg: AppConfig) =>
      val cl    = InfluxClient[IO](client, cfg, console)
      val point = InfluxPoint(
        "m1",
        Map("created" -> created.toString, "project" -> "org/proj", "deprecated" -> "false"),
        Map("bytes"   -> "1234"),
        Some(updated)
      )
      for {
        dbResult <- cl.write(point)
        err      <- console.errQueue.tryDequeue1
        _         = err shouldEqual None
        _         = dbResult shouldEqual Right(())
      } yield ()
    }

    "fail to write an invalid point" in { (client: Client[IO], console: TestConsole[IO], cfg: AppConfig) =>
      val cl    = InfluxClient[IO](client, cfg, console)
      val point = InfluxPoint("m2", Map.empty, Map.empty, Some(updated))
      for {
        result <- cl.write(point)
        err    <- console.errQueue.tryDequeue1
        _       = err shouldEqual None
        _       = result shouldEqual Left(ClientStatusError(Status.BadRequest, """"some write error""""))
      } yield ()
    }

    "perform an influxQL query" in { (client: Client[IO], console: TestConsole[IO], cfg: AppConfig) =>
      val cl = InfluxClient[IO](client, cfg, console)
      for {
        dbResult <- cl.query(allowedQuery)
        err      <- console.errQueue.tryDequeue1
        _         = err shouldEqual None
        _         = dbResult shouldEqual Right(influxQlResultsJson)
      } yield ()
    }
  }
}
