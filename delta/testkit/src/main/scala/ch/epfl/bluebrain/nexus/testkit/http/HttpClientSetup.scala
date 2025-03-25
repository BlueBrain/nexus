package ch.epfl.bluebrain.nexus.testkit.http

import akka.actor.ActorSystem
import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.testkit.actor.ActorSystemSetup

object HttpClientSetup {

  def apply(
      compression: Boolean
  ): Resource[IO, (HttpClient, ActorSystem)] = {
    implicit val httpConfig: HttpClientConfig = HttpClientConfig.noRetry(compression = compression)

    ActorSystemSetup.resource().map(implicit as => (HttpClient(), as))
  }
}
