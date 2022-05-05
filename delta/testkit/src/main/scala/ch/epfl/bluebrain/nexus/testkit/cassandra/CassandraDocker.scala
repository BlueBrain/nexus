package ch.epfl.bluebrain.nexus.testkit.cassandra

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker.CassandraHostConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps

trait CassandraDocker extends BeforeAndAfterAll { this: Suite =>

  protected val container: CassandraContainer =
    new CassandraContainer()
      .withReuse(false)
      .withStartupTimeout(60.seconds.toJava)

  def hostConfig: CassandraHostConfig =
    CassandraHostConfig(container.getHost, container.getMappedPort(9042))

  def actorSystemConfig: Config =
    ConfigFactory
      .parseString(s"""datastax-java-driver.basic.contact-points = ["${hostConfig.host}:${hostConfig.port}"]""")
      .withFallback(ConfigFactory.parseResources("cassandra-test.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()

  def actorSystem: ActorSystem[Nothing] =
    akka.actor.ActorSystem("AkkaPersistenceCassandraSpec", actorSystemConfig).toTyped

  override def beforeAll(): Unit = {
    super.beforeAll()
    container.start()
  }

  override def afterAll(): Unit = {
    container.stop()
    super.afterAll()
  }
}

object CassandraDocker {
  final case class CassandraHostConfig(host: String, port: Int)
}
