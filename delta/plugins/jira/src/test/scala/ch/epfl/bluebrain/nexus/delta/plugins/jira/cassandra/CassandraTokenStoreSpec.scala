package ch.epfl.bluebrain.nexus.delta.plugins.jira.cassandra

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.jira.{TokenStore, TokenStoreSpec}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.CassandraConfig
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker.{cassandraHostConfig, CassandraSpec}
import com.typesafe.config.{Config, ConfigFactory}
import com.whisk.docker.scalatest.DockerTestKit
import akka.actor.typed.scaladsl.adapter._

class CassandraTokenStoreSpec extends CassandraSpec with DockerTestKit with TokenStoreSpec {

  val cassandraConfig: CassandraConfig = CassandraConfig(
    Set(s"${cassandraHostConfig.host}:${cassandraHostConfig.port}"),
    "delta",
    "delta_snapshot",
    "cassandra",
    Secret("cassandra"),
    keyspaceAutocreate = true,
    tablesAutocreate = true
  )

  lazy val actorSystemConfig: Config = {
    val cassandra = cassandraHostConfig
    ConfigFactory
      .parseString(s"""datastax-java-driver.basic.contact-points = ["${cassandra.host}:${cassandra.port}"]""")
      .withFallback(ConfigFactory.parseResources("cassandra-test.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()
  }

  implicit lazy val actorSystem: ActorSystem[Nothing] =
    akka.actor.ActorSystem("CassandraTokenStoreSpec", actorSystemConfig).toTyped

  override def tokenStore: TokenStore = CassandraTokenStore(cassandraConfig).accepted
}
