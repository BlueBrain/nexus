package ch.epfl.bluebrain.nexus.delta.sourcing.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class ProjectLastUpdateConfig(batch: BatchConfig, query: QueryConfig)

object ProjectLastUpdateConfig {
  implicit final val projectLastUpdateConfig: ConfigReader[ProjectLastUpdateConfig] =
    deriveReader[ProjectLastUpdateConfig]
}
