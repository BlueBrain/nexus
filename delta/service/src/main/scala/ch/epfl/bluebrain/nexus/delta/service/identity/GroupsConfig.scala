package ch.epfl.bluebrain.nexus.delta.service.identity

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.config.AggregateConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for groups
  * @param aggregate      the aggregate configuration
  * @param retryStrategy  the retry configuration
  * @param passivateAfter duration after passivation must occur
  */
final case class GroupsConfig(
    aggregate: AggregateConfig,
    retryStrategy: RetryStrategyConfig,
    passivateAfter: FiniteDuration
)
object GroupsConfig {
  implicit final val groupsConfigReader: ConfigReader[GroupsConfig] =
    deriveReader[GroupsConfig]
}
