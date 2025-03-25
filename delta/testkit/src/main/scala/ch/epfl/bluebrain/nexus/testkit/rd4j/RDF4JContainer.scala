package ch.epfl.bluebrain.nexus.testkit.rd4j

import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.testkit.rd4j.RDF4JContainer.ImageName
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

class RDF4JContainer extends GenericContainer[RDF4JContainer](DockerImageName.parse(ImageName)) {
  addExposedPort(8080)
  setWaitStrategy(Wait.forHttp("/rdf4j-server").forStatusCode(200))
}

object RDF4JContainer {

  private val ImageName = "eclipse/rdf4j-workbench:5.1.0"

  /**
    * A running RDF4J container wrapped in a Resource. The container will be stopped upon release.
    */
  def resource(): Resource[IO, RDF4JContainer] = {
    def createAndStartContainer = {
      val container = new RDF4JContainer()
        .withReuse(false)
        .withStartupTimeout(60.seconds.toJava)
      container.start()
      container
    }
    Resource.make(IO.delay(createAndStartContainer))(container => IO.delay(container.stop()))
  }

}
