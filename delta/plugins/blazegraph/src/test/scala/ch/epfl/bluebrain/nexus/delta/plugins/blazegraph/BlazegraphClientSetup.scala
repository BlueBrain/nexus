package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.ActorSystem
import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientSetup
import ch.epfl.bluebrain.nexus.testkit.blazegraph.BlazegraphContainer
import munit.CatsEffectSuite
import munit.catseffect.IOFixture

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

  trait Fixture { self: CatsEffectSuite =>
    val blazegraphClient: IOFixture[BlazegraphClient] =
      ResourceSuiteLocalFixture("blazegraphClient", resource())
  }

}
