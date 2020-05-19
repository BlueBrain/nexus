package ch.epfl.bluebrain.nexus.routes

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.event.Logging
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import ch.epfl.bluebrain.nexus.sourcing.projections.Projections
import ch.epfl.bluebrain.nexus.sourcing.projections.Projections.cassandraDefaultConfigPath

import scala.concurrent.{ExecutionContext, Future}

trait CassandraHeath extends Extension {

  /**
    * Performs a query against cassandra DB to check the connectivity.
    *
    * @return Future(true) when cassandra DB is accessible from within the app
    *         Future(false) otherwise
    */
  def check: Future[Boolean]
}

object CassandraHeath extends ExtensionId[CassandraHeath] with ExtensionIdProvider {

  override def lookup(): ExtensionId[_ <: Extension] = CassandraHeath

  override def createExtension(as: ExtendedActorSystem): CassandraHeath = {
    implicit val ec: ExecutionContext = as.dispatcher
    val log                           = Logging(as, "CassandraHeathCheck")
    val config                        = Projections.journalConfig(as)
    val session                       = CassandraSessionRegistry.get(as).sessionFor(CassandraSessionSettings(cassandraDefaultConfigPath))
    val keyspace: String              = config.getString("keyspace")

    new CassandraHeath {
      private val query = s"SELECT now() FROM $keyspace.messages;"

      override def check: Future[Boolean] = {
        session.selectOne(query).map(_ => true).recover {
          case err =>
            log.error("Error while attempting to query for health check", err)
            false
        }
      }
    }
  }
}
