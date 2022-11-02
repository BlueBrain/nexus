package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.ActorSystem
import cats.effect.Resource
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientSetup
import ch.epfl.bluebrain.nexus.testkit.bio.ResourceFixture.TaskFixture
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, ResourceFixture}
import ch.epfl.bluebrain.nexus.testkit.blazegraph.BlazegraphContainer
import monix.bio.Task
import monix.execution.Scheduler

import scala.concurrent.duration._

object BlazegraphClientSetup {

  private def resource(
      blazegraph: Resource[Task, BlazegraphContainer]
  )(implicit s: Scheduler): Resource[Task, BlazegraphClient] = {
    for {
      (httpClient, actorSystem) <- HttpClientSetup()
      container                 <- blazegraph
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

  def suiteLocalFixture(name: String)(implicit s: Scheduler): TaskFixture[BlazegraphClient] =
    ResourceFixture.suiteLocal(name, resource(BlazegraphContainer.resource()))

  trait Fixture { self: BioSuite =>
    val blazegraphClient: ResourceFixture.TaskFixture[BlazegraphClient] =
      BlazegraphClientSetup.suiteLocalFixture("blazegraphClient")
  }

}
