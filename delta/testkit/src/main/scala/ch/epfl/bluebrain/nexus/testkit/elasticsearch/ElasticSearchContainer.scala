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
  addEnv("ELASTIC_PASSWORD", password)
  addExposedPort(9200)
  setWaitStrategy(Wait.forLogMessage(".*Active license is now \\[BASIC\\]; Security is enabled.*", 1))

  def version: String = Version
}

object ElasticSearchContainer {
  private val Version = "7.16.2"

  val ElasticSearchUser                         = "elastic"
  val ElasticSearchPassword                     = "password"
  val Credentials: Option[BasicHttpCredentials] = Some(BasicHttpCredentials(ElasticSearchUser, ElasticSearchPassword))

  final case class ElasticSearchHostConfig(host: String, port: Int) {
    def endpoint: String = s"http://$host:$port"
  }

  implicit lazy val credentials: Option[BasicHttpCredentials] = Some(BasicHttpCredentials("elastic", "password"))

  /**
    * A running elasticsearch container wrapped in a Resource. The container will be stopped upon release.
    */
  def resource(): Resource[Task, ElasticSearchContainer] = {
    def createAndStartContainer = {
      val container = new ElasticSearchContainer(ElasticSearchPassword)
        .withReuse(false)
        .withStartupTimeout(60.seconds.toJava)
      container.start()
      container
    }
    Resource.make(Task.delay(createAndStartContainer))(container => Task.delay(container.stop()))
  }
}
