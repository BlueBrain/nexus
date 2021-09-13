package ch.epfl.bluebrain.nexus.delta.sourcing.config

import ch.epfl.bluebrain.nexus.delta.sourcing.SnapshotStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.{EventSourceProcessorConfig, StopStrategyConfig}
import pureconfig.ConfigReader

/**
  * Aggregate configuration.
  *
  * @param stopStrategy
  *   the stop strategy configuration for this aggregate [[EventDefinition]]
  * @param snapshotStrategy
  *   the snapshot strategy configuration for this aggregate [[EventDefinition]]
  * @param processor
  *   the event source processor config
  */
final case class AggregateConfig(
    stopStrategy: StopStrategyConfig,
    snapshotStrategy: SnapshotStrategyConfig,
    processor: EventSourceProcessorConfig
)

object AggregateConfig {
  implicit final val aggregateConfigReader: ConfigReader[AggregateConfig] =
    ConfigReader.fromCursor { cursor =>
      for {
        obj               <- cursor.asObjectCursor
        processor         <- ConfigReader[EventSourceProcessorConfig].from(cursor)
        stopStrategyK     <- obj.atKey("stop-strategy")
        stopStrategy      <- ConfigReader[StopStrategyConfig].from(stopStrategyK)
        snapshotStrategyK <- obj.atKey("snapshot-strategy")
        snapshotStrategy  <- ConfigReader[SnapshotStrategyConfig].from(snapshotStrategyK)
      } yield AggregateConfig(stopStrategy, snapshotStrategy, processor)
    }
}
