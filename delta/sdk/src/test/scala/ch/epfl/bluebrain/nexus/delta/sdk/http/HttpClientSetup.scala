package ch.epfl.bluebrain.nexus.delta.sdk.http

import akka.actor.ActorSystem
import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sdk.akka.ActorSystemSetup

object HttpClientSetup {

  def apply(
      compression: Boolean
  ): Resource[IO, (HttpClient, ActorSystem)] = {
    implicit val httpConfig: HttpClientConfig =
      HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, compression = compression)
    ActorSystemSetup.resource().map(implicit as => (HttpClient(), as))
  }
}
