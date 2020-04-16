package ch.epfl.bluebrain.nexus.cli

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import cats.effect.concurrent.Ref
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.EventStreamClient.LiveEventStreamClient
import ch.epfl.bluebrain.nexus.cli.ProjectClient.{LiveProjectClient, UUIDToLabel}
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig
import ch.epfl.bluebrain.nexus.cli.types.Label
import com.github.ghik.silencer.silent
import distage.{Injector, ModuleDef}
import io.circe.Json
import izumi.distage.model.definition.Activation
import izumi.distage.model.definition.StandardAxis.Repo
import izumi.distage.model.plan.GCMode
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.{Headers, Request, Uri}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * A utility to produce an `events.json` file by consuming the event log of a Nexus deployment. To specify an
  * environment configuration and/or token one must temporarily edit the `reference.conf`. The selected project is
  * `tutorialnexus/datamodels` but the source can be changed directly in this file.
  */
@silent
object DumpEvents extends IOApp {
  val org: Label  = Label("tutorialnexus")
  val proj: Label = Label("datamodels")

  override def run(args: List[String]): IO[ExitCode] = {
    val effects = EffectModule[IO]
    val shared  = SharedModule[IO]
    val custom = new ModuleDef {
      make[Client[IO]]
        .tagged(Repo.Prod)
        .fromResource(
          BlazeClientBuilder[IO](ExecutionContext.global)
            .withMaxTotalConnections(10)
            .resource
        )

      make[NexusConfig].fromEffect(IO.delay(NexusConfig()).flatMap(IO.fromEither))

      make[Ref[IO, UUIDToLabel]].fromEffect(Ref.of[IO, UUIDToLabel](Map.empty))
      make[ProjectClient[IO]].tagged(Repo.Prod).from[LiveProjectClient[IO]]
      make[EventStreamClient[IO]].tagged(Repo.Prod).from[LiveEventStreamClient[IO]]
    }

    val modules = effects ++ shared ++ custom
    Injector(Activation(Repo -> Repo.Prod)).produceF[IO](modules, GCMode.NoGC).use { locator =>
      val escF   = locator.get[EventStreamClient[IO]].apply(org, proj, None)
      val client = locator.get[Client[IO]]
      val cfg    = locator.get[NexusConfig]
      escF.flatMap { esc =>
        esc.value
          .take(182)
          .evalMap { ev =>
            val uri = s"${cfg.endpoint}/resources/${ev.organization.value}/${ev.project.value}/_/${URLEncoder
              .encode(ev.resourceId.renderString, StandardCharsets.UTF_8)}?rev=${ev.rev}&format=expanded"
            val req = Request[IO](
              uri = Uri.unsafeFromString(uri),
              headers = Headers(cfg.authorizationHeader.toList)
            )
            client.expect[Json](req).map(expanded => (ev, expanded))
          }
          .map {
            case (ev, expanded) =>
              val rawWithoutSource = ev.raw.mapObject(_.remove("_source"))
              rawWithoutSource deepMerge Json.obj("_source" -> expanded)
          }
          .evalMapAccumulate(0)((idx, json) => IO.delay(println(idx)) >> IO.pure((idx + 1, json)))
          .map(_._2)
          .groupWithin(200, 5.minutes)
          .take(1)
          .map { chunk => Json.fromValues(chunk.toList) }
          .evalMap { json =>
            IO.delay {
              Files.write(Paths.get("events.json"), json.spaces2.getBytes(StandardCharsets.UTF_8))
            }
          }
          .compile
          .drain
          .as(ExitCode.Success)
      }
    }
  }
}
