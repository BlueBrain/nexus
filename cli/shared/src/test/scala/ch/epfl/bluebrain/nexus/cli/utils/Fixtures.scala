package ch.epfl.bluebrain.nexus.cli.utils

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig.{ClientConfig, SSEConfig}
import ch.epfl.bluebrain.nexus.cli.config.{NexusConfig, NexusEndpoints, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.cli.types.{BearerToken, Offset}
import org.http4s.Uri
import org.scalatest.OptionValues

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

trait Fixtures extends Randomness with Resources with OptionValues {
  implicit val ctx: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO]      = IO.timer(global)

  private val token         = Some(BearerToken("mytoken"))
  private val retryStrategy = RetryStrategyConfig("once", 100.millis, 5.seconds, 1, "on-server-error")
  private val client        = ClientConfig(retryStrategy)
  private val sse           = SSEConfig(Some(Offset("bd62f6d0-5c75-11ea-beb1-a5eb66b44d1c").value))

  val config: NexusConfig       = NexusConfig(Uri.unsafeFromString("https://nexus.example.com/v1"), token, client, sse)
  val endpoints: NexusEndpoints = NexusEndpoints(config)
  val nxv: Uri                  = Uri.unsafeFromString("https://bluebrain.github.io/nexus/vocabulary")
}

object Fixtures extends Fixtures
