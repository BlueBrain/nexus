package ch.epfl.bluebrain.nexus.testkit.postgres

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class PostgresContainer(user: String, password: String)
    extends GenericContainer[PostgresContainer](DockerImageName.parse("library/postgres:14.3")) {
  addEnv("POSTGRES_USER", user)
  addEnv("POSTGRES_PASSWORD", password)
  addExposedPort(5432)
  setWaitStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
  setCommand("postgres", "-c", "fsync=off")
}
