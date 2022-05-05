package ch.epfl.bluebrain.nexus.testkit.elasticsearch

import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchContainer.Version
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class ElasticSearchContainer(password: String)
    extends GenericContainer[ElasticSearchContainer](
      DockerImageName.parse(s"docker.elastic.co/elasticsearch/elasticsearch:$Version")
    ) {
  addEnv("ES_JAVA_OPTS", "-Xmx256m")
  addEnv("discovery.type", "single-node")
  addEnv("xpack.security.enabled", "true")
  addEnv("ELASTIC_PASSWORD", password)
  addExposedPort(9200)
  setWaitStrategy(Wait.forLogMessage(".*Active license is now \\[BASIC\\]; Security is enabled.*", 1))

  def version: String = Version
}

object ElasticSearchContainer {
  private val Version = "7.16.2"
}
