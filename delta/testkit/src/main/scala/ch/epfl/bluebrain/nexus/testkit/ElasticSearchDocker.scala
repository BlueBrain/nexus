package ch.epfl.bluebrain.nexus.testkit

import akka.http.scaladsl.model.headers.BasicHttpCredentials
import ch.epfl.bluebrain.nexus.testkit.DockerSupport.DockerKitWithFactory
import ch.epfl.bluebrain.nexus.testkit.ElasticSearchDocker.DefaultPort
import com.whisk.docker.{DockerContainer, DockerReadyChecker, HostConfig}

import scala.concurrent.duration._

trait ElasticSearchDocker extends DockerKitWithFactory {

  override val StartContainersTimeout: FiniteDuration = 1.minute

  val elasticSearchContainer: DockerContainer =
    DockerContainer(s"docker.elastic.co/elasticsearch/elasticsearch:${ElasticSearchDocker.version}")
      .withPorts(DefaultPort -> Some(DefaultPort))
      .withHostConfig(
        HostConfig(memory = Some(384 * 1000000))
      )
      .withEnv(
        "ES_JAVA_OPTS=-Xmx256m",
        "discovery.type=single-node",
        "xpack.security.enabled=true",
        "ELASTIC_PASSWORD=password"
      )
      .withReadyChecker(
        DockerReadyChecker.LogLineContains("Active license is now [BASIC]; Security is enabled")
      )

  override def dockerContainers: List[DockerContainer] =
    elasticSearchContainer :: super.dockerContainers
}

object ElasticSearchDocker {

  val version: String = "7.15.0"

  final case class ElasticSearchHost(host: String, port: Int) {
    def endpoint: String = s"http://$host:$port"
  }

  val DefaultPort: Int                                        = 9200
  lazy val elasticsearchHost: ElasticSearchHost               = ElasticSearchHost("127.0.0.1", DefaultPort)
  implicit lazy val credentials: Option[BasicHttpCredentials] = Some(BasicHttpCredentials("elastic", "password"))
}
