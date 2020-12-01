package ch.epfl.bluebrain.nexus.delta.service.identity

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.service.config.AggregateConfig

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
