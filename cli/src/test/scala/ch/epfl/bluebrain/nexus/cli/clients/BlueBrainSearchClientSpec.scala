package ch.epfl.bluebrain.nexus.cli.clients

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.ClientStatusError
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.utils.{Http4sExtras, TimeTransformation}
import ch.epfl.bluebrain.nexus.cli.{AbstractCliSpec, Console}
import io.circe.Json
import io.circe.parser.parse
import izumi.distage.model.definition.ModuleDef
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.{HttpApp, Response, Status}

class BlueBrainSearchClientSpec extends AbstractCliSpec with Http4sExtras with TimeTransformation {

  private val allowedText      = genString()
  private val allowedPayload   = parse(s"""{"model":"SBIOBERT","text":"$allowedText"}""").toOption.value
  private val expectedResponse = jsonContentOf("/templates/bbs_response.json")
  private val expectedVector   = expectedResponse.hcursor.get[Vector[Double]]("embedding").toOption.value

  override def overrides: ModuleDef =
    new ModuleDef {
      include(defaultModules)
      make[Client[IO]].from {
        val httpApp = HttpApp[IO] {
          case req @ POST -> `v1` / "embed" / "json" =>
            req.as[Json].flatMap {
              case `allowedPayload` => Response[IO](Status.Ok).withEntity(expectedResponse).pure[IO]
              case _                => Response[IO](Status.BadRequest).withEntity("some compute vector").pure[IO]
            }
          case _                                     => Response[IO](Status.BadRequest).withEntity("some other error").pure[IO]
        }
        Client.fromHttpApp(httpApp)
      }
    }

  "A Blue Brain Search Client" should {

    "compute a passed text embeddings" in { (client: Client[IO], console: Console[IO], cfg: AppConfig) =>
      val cl = BlueBrainSearchClient[IO](client, cfg, console)
      for {
        result <- cl.embedding("SBIOBERT", allowedText)
        _       = result.map(_.value) shouldEqual Right(expectedVector)
      } yield ()
    }

    "failed to compute embeddings on wrong text" in { (client: Client[IO], console: Console[IO], cfg: AppConfig) =>
      val cl = BlueBrainSearchClient[IO](client, cfg, console)
      for {
        result <- cl.embedding("SBIOBERT", genString())
        _       = result shouldEqual Left(ClientStatusError(Status.BadRequest, """"some compute vector""""))
      } yield ()
    }
  }
}
