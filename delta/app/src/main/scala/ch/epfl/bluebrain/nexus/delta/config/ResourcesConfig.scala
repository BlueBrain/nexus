package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.sourcing.config.AggregateConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the Resources module.
  *
  * @param aggregate configuration of the underlying aggregate
  */
final case class ResourcesConfig(aggregate: AggregateConfig)

object ResourcesConfig {
  implicit final val resourcesConfigReader: ConfigReader[ResourcesConfig] =
    deriveReader[ResourcesConfig]
}
