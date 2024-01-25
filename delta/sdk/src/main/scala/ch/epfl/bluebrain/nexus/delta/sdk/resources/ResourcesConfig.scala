package ch.epfl.bluebrain.nexus.delta.sdk.resources

import ch.epfl.bluebrain.nexus.delta.sdk.resources.NexusSource.DecodingOption
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the Resources module.
  *
  * @param eventLog
  *   configuration of the event log
  * @param decodingOption
  *   strict/lenient decoding of resources
  * @param skipUpdateNoChange
  *   do not create a new revision when the update does not introduce a change in the current resource state
  */
final case class ResourcesConfig(eventLog: EventLogConfig, decodingOption: DecodingOption, skipUpdateNoChange: Boolean)

object ResourcesConfig {
  implicit final val resourcesConfigReader: ConfigReader[ResourcesConfig] =
    deriveReader[ResourcesConfig]
}
