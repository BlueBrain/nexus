package ch.epfl.bluebrain.nexus.testkit.postgres

import cats.effect.{IO, Resource}
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

class PostgresContainer(user: String, password: String, database: String)
    extends GenericContainer[PostgresContainer](DockerImageName.parse("library/postgres:15.6")) {
  addEnv("POSTGRES_USER", user)
  addEnv("POSTGRES_PASSWORD", password)
  addEnv("POSTGRES_DB222", database)
  addExposedPort(5432)
  setWaitStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
  setCommand("postgres", "-c", "fsync=off")
}

object PostgresContainer {

  /**
    * A running postgres container wrapped in a Resource. The container will be stopped upon release.
    */
  def resource(user: String, password: String, database: String): Resource[IO, PostgresContainer] = {
    def createAndStartContainer = IO.blocking {
      val container = new PostgresContainer(user, password, database)
        .withReuse(false)
        .withStartupTimeout(60.seconds.toJava)
      container.start()
      container
    }
    Resource.make(createAndStartContainer)(container => IO.blocking(container.stop()))
  }

}
