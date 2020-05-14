package ch.epfl.bluebrain.nexus.sourcing.projections

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import akka.persistence.cassandra.{ConfigSessionProvider, SessionProvider}
import com.datastax.driver.core.Session
import com.typesafe.config.Config

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/**
  * A cassandra session provider implementation that caches sessions.
  *
  * @param system the underlying actor system
  * @param config the configuration for the session
  */
class SameSessionProvider(system: ActorSystem, config: Config) extends SessionProvider {
  override def connect()(implicit ec: ExecutionContext): Future[Session] =
    SameSessionProvider.session(system, config)
}

object SameSessionProvider {
  private val map: ConcurrentHashMap[(String, Config), Promise[Session]] =
    new ConcurrentHashMap[(String, Config), Promise[Session]]()

  private def session(system: ActorSystem, config: Config)(implicit ec: ExecutionContext): Future[Session] = {
    map
      .computeIfAbsent(
        (system.name, config), {
          case (_, c) =>
            val p = Promise[Session]().completeWith(new ConfigSessionProvider(system, c).connect())
            p.future.andThen {
              case Success(_)           =>
              case Failure(NonFatal(_)) => map.remove((system.name, config))
            }
            p
        }
      )
      .future
  }
}
