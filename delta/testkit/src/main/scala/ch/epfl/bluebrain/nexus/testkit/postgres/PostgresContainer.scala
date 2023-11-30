package ch.epfl.bluebrain.nexus.testkit.postgres

import cats.effect.{IO, Resource}
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

class PostgresContainer(user: String, password: String)
    extends GenericContainer[PostgresContainer](DockerImageName.parse("library/postgres:15.5")) {
  addEnv("POSTGRES_USER", user)
  addEnv("POSTGRES_PASSWORD", password)
  addExposedPort(5432)
  setWaitStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
  setCommand("postgres", "-c", "fsync=off")
}

object PostgresContainer {

  /**
    * A running postgres container wrapped in a Resource. The container will be stopped upon release.
    * @param user
    *   the db username
    * @param password
    *   the db password
    */
  def resource(user: String, password: String): Resource[IO, PostgresContainer] = {
    def createAndStartContainer = IO.blocking {
      val container = new PostgresContainer(user, password)
        .withReuse(false)
        .withStartupTimeout(60.seconds.toJava)
      container.start()
      container
    }
    Resource.make(createAndStartContainer)(container => IO.blocking(container.stop()))
  }

}
