package ch.epfl.bluebrain.nexus.cli.utils

import cats.effect.{ContextShift, IO}
import ch.epfl.bluebrain.nexus.cli.config.{NexusConfig, NexusEndpoints}
import ch.epfl.bluebrain.nexus.cli.types.BearerToken
import org.http4s.Uri

import scala.concurrent.ExecutionContext.Implicits.global

trait Fixtures extends Randomness with Resources {
  implicit val ctx: ContextShift[IO] = IO.contextShift(global)

  val token: Option[BearerToken] = Some(BearerToken(genString()))
  val config                     = NexusConfig(Uri.unsafeFromString("http://example.nexus.com.ch/v1"), token)
  val endpoints                  = NexusEndpoints(config)

  val nxv: Uri = Uri.unsafeFromString("https://bluebrain.github.io/nexus/vocabulary")
}

object Fixtures extends Fixtures
