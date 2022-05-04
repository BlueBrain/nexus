package ch.epfl.bluebrain.nexus.testkit.cassandra

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class CassandraContainer extends GenericContainer[CassandraContainer](DockerImageName.parse("cassandra:3.11.11")) {
  addEnv("JVM_OPTS", "-Xms512m -Xmx512m -Dcassandra.initial_token=0 -Dcassandra.skip_wait_for_gossip_to_settle=0")
  addEnv("MAX_HEAP_SIZE", "512m")
  addEnv("HEAP_NEWSIZE", "100m")
  addExposedPort(9042)
  setWaitStrategy(Wait.forLogMessage(".*Created default superuser role.*", 1))
}
