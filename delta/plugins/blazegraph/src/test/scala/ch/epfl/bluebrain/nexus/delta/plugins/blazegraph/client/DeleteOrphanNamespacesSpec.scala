package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig.Credentials
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

/**
  * To delete orphan namespaces until it is a scheduled in some way
  */
@DoNotDiscover
class DeleteOrphanNamespacesSpec
    extends TestKit(ActorSystem("DeleteOrphanNamespacesSpec"))
    with AnyWordSpecLike
    with Matchers
    with IOValues
    with ConfigFixtures {

  implicit private val httpCfg: HttpClientConfig = httpClientConfig
  implicit private val sc: Scheduler             = Scheduler.global

  private val endpoint = Uri("???")
  private val username = "???"
  private val password = Secret("???")

  private lazy val client = BlazegraphClient(HttpClient(), endpoint, Some(Credentials(username, password)), 10.seconds)

  "Deleting orphan namespaces" should {

    "delete existing namespaces" in {
      val result = client
        .listOutdatedNamespaces()
        .flatMap(
          _.value.toList
            .traverse { n =>
              client.deleteNamespace(n).tapEval { b => UIO.delay(println(s"$n -> $b")) }
            }
        )
        .accepted
      println(result.size)
    }
  }

}
