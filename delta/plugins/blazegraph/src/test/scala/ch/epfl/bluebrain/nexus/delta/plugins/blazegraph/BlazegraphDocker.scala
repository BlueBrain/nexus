package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphDocker.DefaultPort
import ch.epfl.bluebrain.nexus.testkit.DockerSupport.DockerKitWithTimeouts
import com.whisk.docker.{DockerContainer, DockerReadyChecker}

import scala.concurrent.duration._

trait BlazegraphDocker extends DockerKitWithTimeouts {

  val blazegraphContainer: DockerContainer = DockerContainer("bluebrain/blazegraph-nexus:2.1.5")
    .withEnv(
      "JAVA_OPTS=-Djava.awt.headless=true -XX:MaxDirectMemorySize=64m -Xmx256m -XX:+UseG1GC"
    )
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

}
