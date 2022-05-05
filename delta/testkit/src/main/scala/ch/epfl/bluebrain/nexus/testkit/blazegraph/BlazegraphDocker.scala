package ch.epfl.bluebrain.nexus.testkit.blazegraph

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.testkit.blazegraph.BlazegraphDocker.BlazegraphHostConfig
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

trait BlazegraphDocker extends BeforeAndAfterAll { this: Suite =>

  private val container: BlazegraphContainer =
    new BlazegraphContainer()
      .withReuse(false)
      .withStartupTimeout(60.seconds.toJava)

  def hostConfig: BlazegraphHostConfig =
    BlazegraphHostConfig(container.getHost, container.getMappedPort(9999))

  override def beforeAll(): Unit = {
    super.beforeAll()
    container.start()
  }

  override def afterAll(): Unit = {
    container.stop()
    super.afterAll()
  }
}

object BlazegraphDocker {

  final case class BlazegraphHostConfig(host: String, port: Int) {
    def endpoint: Uri = s"http://$host:$port/blazegraph"
  }

}
