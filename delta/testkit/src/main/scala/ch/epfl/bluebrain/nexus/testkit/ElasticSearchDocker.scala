package ch.epfl.bluebrain.nexus.testkit

import akka.http.scaladsl.model.headers.BasicHttpCredentials
import ch.epfl.bluebrain.nexus.testkit.DockerSupport.DockerKitWithFactory
import ch.epfl.bluebrain.nexus.testkit.ElasticSearchDocker.DefaultPort
import com.whisk.docker.{DockerContainer, DockerReadyChecker}

import scala.concurrent.duration._

trait ElasticSearchDocker extends DockerKitWithFactory {

  override val StartContainersTimeout: FiniteDuration = 1.minute

  val elasticSearchContainer: DockerContainer = DockerContainer("docker.elastic.co/elasticsearch/elasticsearch:7.13.4")
    .withPorts(DefaultPort -> Some(DefaultPort))
    .withEnv(
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

  final case class ElasticSearchHost(host: String, port: Int) {
    def endpoint: String = s"http://$host:$port"
  }

  val DefaultPort: Int                                        = 9200
  lazy val elasticsearchHost: ElasticSearchHost               = ElasticSearchHost("127.0.0.1", DefaultPort)
  implicit lazy val credentials: Option[BasicHttpCredentials] = Some(BasicHttpCredentials("elastic", "password"))
}
