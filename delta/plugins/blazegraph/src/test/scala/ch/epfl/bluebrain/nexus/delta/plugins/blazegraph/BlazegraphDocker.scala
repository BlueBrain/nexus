package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.DefaultPort
import ch.epfl.bluebrain.nexus.testkit.DockerSupport.DockerKitWithFactory
import com.whisk.docker.scalatest.DockerTestKit
import com.whisk.docker.{DockerContainer, DockerReadyChecker}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

trait BlazegraphDocker extends DockerKitWithFactory {

  val blazegraphContainer: DockerContainer = DockerContainer("bluebrain/blazegraph-nexus:2.1.5")
    .withPorts(DefaultPort -> Some(DefaultPort))
    .withReadyChecker(
      DockerReadyChecker.HttpResponseCode(DefaultPort).looped(30, 1.second)
    )

  override def dockerContainers: List[DockerContainer] =
    blazegraphContainer :: super.dockerContainers
}

object BlazegraphDocker {

  val DefaultPort = 9999

  lazy val blazegraphHostConfig: BlazegraphHostConfig =
    BlazegraphHostConfig(
      "127.0.0.1",
      DefaultPort
    )

  final case class BlazegraphHostConfig(host: String, port: Int) {
    def endpoint: Uri = s"http://$host:$port/blazegraph"
  }

  trait BlazegraphSpec extends AnyWordSpecLike with Matchers with BlazegraphDocker with DockerTestKit
}
