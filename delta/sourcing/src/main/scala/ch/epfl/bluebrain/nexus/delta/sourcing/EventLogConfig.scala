package ch.epfl.bluebrain.nexus.delta.sourcing

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration related to the event log
  * @param flavour
  *   the database flavour
  * @param firstOffset
  *   Default offset to fetch from the beginning
  * @param tagQueriesStateTimeToLive
  *   How long eventByTag queries keep state about persistence ids
  */
final case class EventLogConfig(
    flavour: DatabaseFlavour,
    firstOffset: Offset,
    tagQueriesStateTimeToLive: Option[FiniteDuration]
)

object EventLogConfig {

  val postgresql = EventLogConfig(DatabaseFlavour.Postgres, NoOffset, None)

}
