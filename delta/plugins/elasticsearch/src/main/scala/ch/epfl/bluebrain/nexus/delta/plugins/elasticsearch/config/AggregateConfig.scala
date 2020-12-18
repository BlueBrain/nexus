package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config

import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.{EventSourceProcessorConfig, StopStrategyConfig}

/**
  * Aggregate configuration.
  *
  * @param stopStrategy     the stop strategy configuration for this aggregate [[EventDefinition]]
  * @param snapshotStrategy the snapshot strategy configuration for this aggregate [[EventDefinition]]
  * @param processor        the event source processor config
  */
final case class AggregateConfig(
    stopStrategy: StopStrategyConfig,
    snapshotStrategy: SnapshotStrategyConfig,
    processor: EventSourceProcessorConfig
)
