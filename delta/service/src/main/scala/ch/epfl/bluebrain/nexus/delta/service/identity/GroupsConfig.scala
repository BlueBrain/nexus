package ch.epfl.bluebrain.nexus.delta.service.identity

import ch.epfl.bluebrain.nexus.delta.sourcing.config.AggregateConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for groups
  * @param aggregate
  *   the aggregate configuration
  * @param passivateAfter
  *   duration after passivation must occur
  */
final case class GroupsConfig(
    aggregate: AggregateConfig,
    passivateAfter: FiniteDuration
)
object GroupsConfig {
  implicit final val groupsConfigReader: ConfigReader[GroupsConfig] =
    deriveReader[GroupsConfig]
}
