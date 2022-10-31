package ch.epfl.bluebrain.nexus.testkit.blazegraph

import cats.effect.Resource
import monix.bio.Task
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
  def resource(): Resource[Task, BlazegraphContainer] = {
    def createAndStartContainer = {
      val container = new BlazegraphContainer()
        .withReuse(false)
        .withStartupTimeout(60.seconds.toJava)
      container.start()
      container
    }
    Resource.make(Task.delay(createAndStartContainer))(container => Task.delay(container.stop()))
  }

}
