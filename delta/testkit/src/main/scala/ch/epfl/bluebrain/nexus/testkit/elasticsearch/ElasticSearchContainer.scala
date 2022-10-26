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
  addEnv("ingest.geoip.downloader.enabled", "false")
  addEnv("ELASTIC_PASSWORD", password)
  addExposedPort(9200)
  setWaitStrategy(Wait.forLogMessage(".*(\"message\":\\s?\"started[\\s?|\"].*|] started\n$)", 1))

  def version: String = Version
}

object ElasticSearchContainer {
  private val Version = "8.4.3"
}
