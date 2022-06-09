package ch.epfl.bluebrain.nexus.delta.sourcing.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

final case class EventLogConfig(queryConfig: QueryConfig, maxDuration: FiniteDuration)

object EventLogConfig {
  implicit final val eventLogConfig: ConfigReader[EventLogConfig] =
    deriveReader[EventLogConfig]
}
