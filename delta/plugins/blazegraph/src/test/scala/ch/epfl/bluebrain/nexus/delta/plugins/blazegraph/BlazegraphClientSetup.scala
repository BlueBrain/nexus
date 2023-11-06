package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.actor.ActorSystem
import cats.effect.Resource
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientSetup
import ch.epfl.bluebrain.nexus.testkit.bio.BioRunContext
import ch.epfl.bluebrain.nexus.testkit.blazegraph.BlazegraphContainer
import ch.epfl.bluebrain.nexus.testkit.mu.bio.ResourceFixture
import ch.epfl.bluebrain.nexus.testkit.mu.bio.ResourceFixture.TaskFixture
import monix.bio.Task
import monix.execution.Scheduler

import scala.concurrent.duration._

object BlazegraphClientSetup {

  def resource()(implicit s: Scheduler): Resource[Task, BlazegraphClient] = {
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

  def suiteLocalFixture(name: String)(implicit s: Scheduler): TaskFixture[BlazegraphClient] =
    ResourceFixture.suiteLocal(name, resource())

  trait Fixture { self: BioRunContext =>
    val blazegraphClient: ResourceFixture.TaskFixture[BlazegraphClient] =
      BlazegraphClientSetup.suiteLocalFixture("blazegraphClient")
  }

}
