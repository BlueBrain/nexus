package ch.epfl.bluebrain.nexus.delta

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, DatabaseFlavour}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.toEnvelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import monix.bio.Task

import scala.reflect.ClassTag

package object wiring {

  /**
    * Wires the [[EventLog]] for the database activated in the configuration for the passed [[Event]] type
    *
    * @param cfg the configuration
    * @tparam A the event type
    */
  def databaseEventLog[A <: Event: ClassTag](cfg: AppConfig, as: ActorSystem[Nothing]): Task[EventLog[Envelope[A]]] =
    cfg.database.flavour match {
      case DatabaseFlavour.Postgres  => EventLog.postgresEventLog(toEnvelope[A])(as)
      case DatabaseFlavour.Cassandra => EventLog.cassandraEventLog(toEnvelope[A])(as)
    }
}
