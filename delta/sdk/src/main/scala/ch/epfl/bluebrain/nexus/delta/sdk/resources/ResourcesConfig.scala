package ch.epfl.bluebrain.nexus.delta.sdk.resources

import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the Resources module.
  *
  * @param eventLog
  *   configuration of the event log
  */
final case class ResourcesConfig(eventLog: EventLogConfig)

object ResourcesConfig {
  implicit final val resourcesConfigReader: ConfigReader[ResourcesConfig] =
    deriveReader[ResourcesConfig]
}
