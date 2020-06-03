package ch.epfl.bluebrain.nexus.admin.routes

import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import ch.epfl.bluebrain.nexus.admin.routes.HealthChecker._
import ch.epfl.bluebrain.nexus.sourcing.projections.Projections.{cassandraDefaultConfigPath, journalConfig}

import scala.concurrent.{ExecutionContext, Future}

/**
  *  The Cassandra health checker
  */
class CassandraHealthChecker()(implicit as: ActorSystem) extends HealthChecker {
  implicit private val ec: ExecutionContext = as.dispatcher

  private val log              = Logging(as, "CassandraHeathCheck")
  private val keyspace: String = journalConfig(as).getString("keyspace")
  private val session =
    CassandraSessionRegistry.get(as).sessionFor(CassandraSessionSettings(cassandraDefaultConfigPath))
  private val query = s"SELECT now() FROM $keyspace.messages;"

  override def check: Future[Status] =
    session
      .selectOne(query)
      .map(_ => Up: Status)
      .recover {
        case err =>
          log.error("Error while attempting to query for health check", err)
          Inaccessible
      }
}

object CassandraHealthChecker {
  def apply()(implicit as: ActorSystem): CassandraHealthChecker =
    new CassandraHealthChecker()(as)
}
