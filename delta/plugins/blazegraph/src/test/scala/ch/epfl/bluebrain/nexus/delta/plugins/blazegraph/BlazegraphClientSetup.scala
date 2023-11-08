package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.ActorSystem
import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientSetup
import ch.epfl.bluebrain.nexus.testkit.bio.BioRunContext
import ch.epfl.bluebrain.nexus.testkit.blazegraph.BlazegraphContainer
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture.IOFixture
import monix.execution.Scheduler

import scala.concurrent.duration._

object BlazegraphClientSetup {

  def resource()(implicit s: Scheduler): Resource[IO, BlazegraphClient] = {
    for {
      (httpClient, actorSystem) <- HttpClientSetup(compression = false)
      container                 <- BlazegraphContainer.resource()
    } yield {
      implicit val as: ActorSystem = actorSystem
      BlazegraphClient(
        httpClient,
        s"http://${container.getHost}:${container.getMappedPort(9999)}/blazegraph",
        None,
        10.seconds
      )
    }
  }

  def suiteLocalFixture(name: String)(implicit s: Scheduler): IOFixture[BlazegraphClient] =
    ResourceFixture.suiteLocal(name, resource())

  trait Fixture { self: BioRunContext =>
    val blazegraphClient: ResourceFixture.IOFixture[BlazegraphClient] =
      BlazegraphClientSetup.suiteLocalFixture("blazegraphClient")
  }

}
