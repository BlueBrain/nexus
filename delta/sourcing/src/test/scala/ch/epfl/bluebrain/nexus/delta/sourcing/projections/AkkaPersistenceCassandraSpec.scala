package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.sourcing.config.CassandraConfig
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker
import com.typesafe.config.{Config, ConfigFactory}

object AkkaPersistenceCassandraSpec {

  def cassandraConfig(docker: CassandraDocker): CassandraConfig =
    CassandraConfig(
      Set(s"${docker.hostConfig.host}:${docker.hostConfig.port}"),
      "delta",
      "delta_snapshot",
      "cassandra",
      Secret("cassandra"),
      keyspaceAutocreate = true,
      tablesAutocreate = true
    )

  def actorSystemConfig(docker: CassandraDocker): Config =
    ConfigFactory
      .parseString(
        s"""datastax-java-driver.basic.contact-points = ["${docker.hostConfig.host}:${docker.hostConfig.port}"]"""
      )
      .withFallback(ConfigFactory.parseResources("cassandra-test.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()

  def actorSystem(docker: CassandraDocker): ActorSystem[Nothing] =
    akka.actor.ActorSystem("AkkaPersistenceCassandraSpec", actorSystemConfig(docker)).toTyped
}
