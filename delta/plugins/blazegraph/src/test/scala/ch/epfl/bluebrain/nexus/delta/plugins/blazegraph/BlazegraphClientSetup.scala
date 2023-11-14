package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.ActorSystem
import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientSetup
import ch.epfl.bluebrain.nexus.testkit.blazegraph.BlazegraphContainer
import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture.IOFixture

import scala.concurrent.duration._

object BlazegraphClientSetup extends Fixtures {

  def resource(): Resource[IO, BlazegraphClient] = {

    for {
      (httpClient, actorSystem) <- HttpClientSetup(compression = false)
      container                 <- BlazegraphContainer.resource()
      props                     <- Resource.eval(defaultProperties)
    } yield {
      implicit val as: ActorSystem = actorSystem
      BlazegraphClient(
        httpClient,
        s"http://${container.getHost}:${container.getMappedPort(9999)}/blazegraph",
        None,
        10.seconds,
        props
      )
    }
  }

  def suiteLocalFixture(
      name: String
  ): IOFixture[BlazegraphClient] =
    ResourceFixture.suiteLocal(name, resource())

  trait Fixture { self: CatsRunContext =>
    val blazegraphClient: ResourceFixture.IOFixture[BlazegraphClient] =
      BlazegraphClientSetup.suiteLocalFixture("blazegraphClient")
  }

}
