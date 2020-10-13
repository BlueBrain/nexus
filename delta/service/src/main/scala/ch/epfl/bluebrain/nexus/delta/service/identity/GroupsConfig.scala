package ch.epfl.bluebrain.nexus.delta.service.identity

import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig

import scala.concurrent.duration.FiniteDuration

final case class GroupsConfig(
    aggregate: AggregateConfig,
    retryConfig: RetryStrategyConfig,
    maxAfterLastInteraction: Option[FiniteDuration]
) {}
