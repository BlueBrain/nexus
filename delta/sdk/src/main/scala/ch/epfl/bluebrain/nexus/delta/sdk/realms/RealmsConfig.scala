package ch.epfl.bluebrain.nexus.delta.sdk.realms

import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the Realms module.
  *
  * @param eventLog
  *   The event log configuration
  * @param pagination
  *   configuration for how pagination should behave in listing operations
  */
final case class RealmsConfig(
    eventLog: EventLogConfig,
    pagination: PaginationConfig
)

object RealmsConfig {
  implicit final val realmsConfigReader: ConfigReader[RealmsConfig] =
    deriveReader[RealmsConfig]
}
