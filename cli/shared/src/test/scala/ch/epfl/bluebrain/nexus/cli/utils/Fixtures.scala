package ch.epfl.bluebrain.nexus.cli.utils

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.cli.config.{NexusConfig, NexusEndpoints, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.cli.types.BearerToken
import org.http4s.Uri

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

trait Fixtures extends Randomness with Resources {
  implicit val ctx: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO]      = IO.timer(global)

  val token: Option[BearerToken] = Some(BearerToken("mytoken"))
  val retryStrategy              = RetryStrategyConfig("once", 100.millis, 5.seconds, 1, "onServerError")
  val config                     = NexusConfig(Uri.unsafeFromString("https://nexus.example.com/v1"), token, retryStrategy)
  val endpoints                  = NexusEndpoints(config)

  val nxv: Uri = Uri.unsafeFromString("https://bluebrain.github.io/nexus/vocabulary")
}

object Fixtures extends Fixtures
