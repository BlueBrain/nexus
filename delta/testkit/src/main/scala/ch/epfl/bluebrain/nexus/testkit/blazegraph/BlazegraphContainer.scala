package ch.epfl.bluebrain.nexus.testkit.blazegraph

import cats.effect.{IO, Resource}
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

class BlazegraphContainer
    extends GenericContainer[BlazegraphContainer](DockerImageName.parse("bluebrain/blazegraph-nexus:2.1.6-RC")) {
  addEnv("JAVA_OPTS", "-Djava.awt.headless=true -XX:MaxDirectMemorySize=64m -Xmx256m -XX:+UseG1GC")
  addExposedPort(9999)
  setWaitStrategy(Wait.forHttp("/blazegraph").forStatusCode(200))
}

object BlazegraphContainer {

  /**
    * A running blazegraph container wrapped in a Resource. The container will be stopped upon release.
    */
  def resource(): Resource[IO, BlazegraphContainer] = {
    def createAndStartContainer = {
      val container = new BlazegraphContainer()
        .withReuse(false)
        .withStartupTimeout(60.seconds.toJava)
      container.start()
      container
    }
    Resource.make(IO.delay(createAndStartContainer))(container => IO.delay(container.stop()))
  }

}
