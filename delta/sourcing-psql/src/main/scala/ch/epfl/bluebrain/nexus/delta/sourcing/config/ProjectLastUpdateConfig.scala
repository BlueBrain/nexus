package ch.epfl.bluebrain.nexus.delta.sourcing.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

final case class ProjectLastUpdateConfig(batch: BatchConfig, query: QueryConfig, inactiveInterval: FiniteDuration)

object ProjectLastUpdateConfig {
  implicit final val projectLastUpdateConfig: ConfigReader[ProjectLastUpdateConfig] =
    deriveReader[ProjectLastUpdateConfig]
}
