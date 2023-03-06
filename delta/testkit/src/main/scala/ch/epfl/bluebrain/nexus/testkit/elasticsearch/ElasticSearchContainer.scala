package ch.epfl.bluebrain.nexus.testkit.elasticsearch

import akka.http.scaladsl.model.headers.BasicHttpCredentials
import cats.effect.Resource
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchContainer.Version
import monix.bio.Task
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

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
  private val Version = "8.6.2"
}
