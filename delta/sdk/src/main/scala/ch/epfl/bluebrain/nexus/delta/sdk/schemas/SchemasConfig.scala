package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the Schemas module.
  *
  * @param eventLog
  *   configuration of the event log
  */
final case class SchemasConfig(eventLog: EventLogConfig)

object SchemasConfig {
  implicit final val schemasConfigReader: ConfigReader[SchemasConfig] =
    deriveReader[SchemasConfig]
}
