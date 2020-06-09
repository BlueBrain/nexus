package ch.epfl.bluebrain.nexus.service.routes

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.event.Logging
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import ch.epfl.bluebrain.nexus.sourcing.projections.Projections._

import scala.concurrent.{ExecutionContext, Future}

trait CassandraHealth extends Extension {

  /**
    * Performs a query against cassandra DB to check the connectivity.
    *
    * @return Future(true) when cassandra DB is accessible from within the app
    *         Future(false) otherwise
    */
  def check: Future[Boolean]
}

object CassandraHealth extends ExtensionId[CassandraHealth] with ExtensionIdProvider {

  override def lookup(): ExtensionId[_ <: Extension] = CassandraHealth

  override def createExtension(as: ExtendedActorSystem): CassandraHealth = {
    implicit val ec: ExecutionContext = as.dispatcher

    val log              = Logging(as, "CassandraHeathCheck")
    val keyspace: String = journalConfig(as).getString("keyspace")
    val session          = CassandraSessionRegistry.get(as).sessionFor(CassandraSessionSettings(cassandraDefaultConfigPath))

    new CassandraHealth {
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
