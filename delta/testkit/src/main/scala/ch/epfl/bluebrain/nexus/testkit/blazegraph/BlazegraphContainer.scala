package ch.epfl.bluebrain.nexus.testkit.blazegraph

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class BlazegraphContainer
    extends GenericContainer[BlazegraphContainer](DockerImageName.parse("bluebrain/blazegraph-nexus:2.1.6-RC")) {
  addEnv("JAVA_OPTS", "-Djava.awt.headless=true -XX:MaxDirectMemorySize=64m -Xmx256m -XX:+UseG1GC")
  addExposedPort(9999)
  setWaitStrategy(Wait.forHttp("/blazegraph").forStatusCode(200))
}
