package ch.epfl.bluebrain.nexus.delta.sdk.http

import akka.actor.ActorSystem
import cats.effect.{ContextShift, IO, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig

import scala.concurrent.ExecutionContext

object HttpClientSetup {

  def apply(
      compression: Boolean
  )(implicit ec: ExecutionContext, cs: ContextShift[IO]): Resource[IO, (HttpClient, ActorSystem)] = {
    implicit val httpConfig: HttpClientConfig =
      HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, compression = compression)
    Resource
      .make[IO, ActorSystem](IO.delay(ActorSystem()))((as: ActorSystem) => IO.delay(as.terminate()).void)
      .map { implicit as =>
        (HttpClient(), as)
      }
  }

}
