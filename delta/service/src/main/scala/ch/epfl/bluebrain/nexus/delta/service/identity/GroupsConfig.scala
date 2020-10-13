package ch.epfl.bluebrain.nexus.delta.service.identity

import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for groups
  * @param aggregate the aggregate configuration
  * @param retryConfig the retry configuration
  * @param passivateAfter duration after passivation must occur
  */
final case class GroupsConfig(
    aggregate: AggregateConfig,
    retryConfig: RetryStrategyConfig,
    passivateAfter: FiniteDuration
)
