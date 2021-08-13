package ch.epfl.bluebrain.nexus.testkit.cassandra

import ch.epfl.bluebrain.nexus.testkit.DockerSupport.DockerKitWithFactory
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker.DefaultCqlPort
import com.whisk.docker.scalatest.DockerTestKit
import com.whisk.docker.{DockerContainer, DockerReadyChecker}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

trait CassandraDocker extends DockerKitWithFactory {

  override val StartContainersTimeout: FiniteDuration = 40.seconds

  val cassandraContainer: DockerContainer = DockerContainer("cassandra:3.11.6")
    .withPorts(DefaultCqlPort -> Some(DefaultCqlPort))
    .withEnv(
      "JVM_OPTS=-Xms1g -Xmx1g -Dcassandra.initial_token=0 -Dcassandra.skip_wait_for_gossip_to_settle=0",
      "MAX_HEAP_SIZE=1g",
      "HEAP_NEWSIZE=100m"
    )
    .withNetworkMode("bridge")
    .withReadyChecker(
      DockerReadyChecker.LogLineContains("Starting listening for CQL clients on")
    )

  override def dockerContainers: List[DockerContainer] =
    cassandraContainer :: super.dockerContainers
}

object CassandraDocker {

  val DefaultCqlPort                           = 9042
  val cassandraHostConfig: CassandraHostConfig = CassandraHostConfig("127.0.0.1", DefaultCqlPort)

  final case class CassandraHostConfig(host: String, port: Int)

  trait CassandraSpec extends AnyWordSpecLike with CassandraDocker with DockerTestKit
}
