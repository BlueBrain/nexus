package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker.{cassandraHostConfig, CassandraSpec}
import com.typesafe.config.{Config, ConfigFactory}
import akka.actor.typed.scaladsl.adapter._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.sourcing.config.CassandraConfig

trait AkkaPersistenceCassandraSpec extends CassandraSpec {

  implicit val actorSystem: ActorSystem[Nothing] =
    AkkaPersistenceCassandraSpec.actorSystem

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }

}

object AkkaPersistenceCassandraSpec {
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

  lazy val actorSystem = akka.actor.ActorSystem("AkkaPersistenceCassandraSpec", actorSystemConfig).toTyped

}
