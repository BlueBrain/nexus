package ch.epfl.bluebrain.nexus.sourcing.projections

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

import akka.actor.ActorSystem
import akka.persistence.cassandra.testkit.CassandraLauncher
import com.typesafe.config.ConfigFactory

/**
  * Various functions for building actor systems to be used during testing.
  */
object SystemBuilder {

  /**
    * @return an unused system port
    */
  final def freePort(): Int = {
    val serverSocket = ServerSocketChannel.open().socket()
    serverSocket.bind(new InetSocketAddress("127.0.0.1", 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  /**
    * Constructs an actor system with akka persistence configured to cassandra.
    *
    * @param name the name of the actor system
    * @return an actor system with akka persistence configured to cassandra
    */
  final def persistence(name: String): ActorSystem = {
    val free = CassandraLauncher.randomPort
    val config = ConfigFactory
      .parseString(s"""
         |test.cassandra-port = $free
       """.stripMargin)
      .withFallback(ConfigFactory.parseResources("cassandra.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()
    ActorSystem(name, config)
  }

  /**
    * Constructs an actor system with akka persistence configured to cassandra and clustering enabled.
    *
    * @param name the name of the actor system
    * @return an actor system with akka persistence configured to cassandra and clustering enabled
    */
  final def cluster(name: String): ActorSystem = {
    val cassandra = CassandraLauncher.randomPort
    val remote    = freePort()
    val config = ConfigFactory
      .parseString(s"""
         |test.cassandra-port = $cassandra
         |test.remote-port = $remote
       """.stripMargin)
      .withFallback(ConfigFactory.parseResources("cluster.conf"))
      .withFallback(ConfigFactory.parseResources("cassandra.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()
    ActorSystem(name, config)
  }
}
