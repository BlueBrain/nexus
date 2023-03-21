package ch.epfl.bluebrain.nexus.delta.sdk.http

import akka.actor.ActorSystem
import cats.effect.Resource
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import monix.bio.Task
import monix.execution.Scheduler

object HttpClientSetup {

  def apply()(implicit s: Scheduler): Resource[Task, (HttpClient, ActorSystem)] = {
    implicit val httpConfig: HttpClientConfig =
      HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, compression = true)
    Resource
      .make[Task, ActorSystem](Task.delay(ActorSystem()))((as: ActorSystem) => Task.delay(as.terminate()).void)
      .map { implicit as =>
        (HttpClient(), as)
      }
  }

}
