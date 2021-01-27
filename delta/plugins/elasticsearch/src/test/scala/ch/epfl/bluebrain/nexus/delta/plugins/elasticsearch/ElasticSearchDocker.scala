package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchDocker.ElasticSearchHost
import ch.epfl.bluebrain.nexus.testkit.DockerSupport.DockerKitWithFactory
import com.whisk.docker.{DockerContainer, DockerReadyChecker}
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

trait ElasticSearchDocker extends DockerKitWithFactory {

  override val StartContainersTimeout: FiniteDuration = 1.minute
  val DefaultPort: Int                                = 9200

  lazy val blazegraphHostConfig: ElasticSearchHost =
    ElasticSearchHost(dockerExecutor.host, DefaultPort)

  val elasticSearchContainer: DockerContainer = DockerContainer("docker.elastic.co/elasticsearch/elasticsearch:7.10.2")
    .withPorts(DefaultPort -> Some(DefaultPort))
    .withEnv("discovery.type=single-node")
    .withReadyChecker(
      DockerReadyChecker.HttpResponseCode(DefaultPort).looped(30, 2.second)
    )

  override def dockerContainers: List[DockerContainer] =
    elasticSearchContainer :: super.dockerContainers
}

object ElasticSearchDocker {

  final case class ElasticSearchHost(host: String, port: Int) {
    def endpoint: Uri = s"http://$host:$port"
  }

  trait ElasticSearchSpec extends AnyWordSpecLike with Matchers with ElasticSearchDocker with DockerTestKit
}
