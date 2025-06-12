package ch.epfl.bluebrain.nexus.testkit.elasticsearch

import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchContainer.Version
import org.http4s.BasicCredentials
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

class ElasticSearchContainer(password: String)
    extends GenericContainer[ElasticSearchContainer](
      DockerImageName.parse(s"docker.elastic.co/elasticsearch/elasticsearch:$Version")
    ) {
  addEnv("ES_JAVA_OPTS", "-Xmx4G")
  addEnv("discovery.type", "single-node")
  addEnv("xpack.security.enabled", "true")
  addEnv("ingest.geoip.downloader.enabled", "false")
  addEnv("ELASTIC_PASSWORD", password)
  addExposedPort(9200)
  setWaitStrategy(Wait.forLogMessage(".*(\"message\":\\s?\"started[\\s?|\"].*|] started\n$)", 1))
}

object ElasticSearchContainer {
  val Version = "9.0.1"

  private val ElasticSearchUser     = "elastic"
  private val ElasticSearchPassword = "password"

  implicit lazy val credentials: Option[BasicCredentials] = Some(
    BasicCredentials(ElasticSearchUser, ElasticSearchPassword)
  )

  /**
    * A running elasticsearch container wrapped in a Resource. The container will be stopped upon release.
    */
  def resource(): Resource[IO, ElasticSearchContainer] = {
    def createAndStartContainer = {
      val container = new ElasticSearchContainer(ElasticSearchPassword)
        .withReuse(false)
        .withStartupTimeout(60.seconds.toJava)
      container.start()
      container
    }
    Resource.make(IO.delay(createAndStartContainer))(container => IO.delay(container.stop()))
  }
}
