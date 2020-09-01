package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker.CassandraSpec
import com.typesafe.config.{Config, ConfigFactory}
import akka.actor.typed.scaladsl.adapter._

trait AkkaPersistenceCassandraSpec extends CassandraSpec {

  val actorSystemConfig: Config = {
    val cassandra = cassandraHostConfig
    ConfigFactory
      .parseString(s"""datastax-java-driver.basic.contact-points = ["${cassandra.host}:${cassandra.port}"]""")
      .withFallback(ConfigFactory.parseResources("cassandra-test.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()
  }

  implicit val actorSystem: ActorSystem[Nothing] =
    akka.actor.ActorSystem("AkkaPersistenceCassandraSpec", actorSystemConfig).toTyped

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }

}
