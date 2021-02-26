package ch.epfl.bluebrain.nexus.delta.sourcing.config

import ch.epfl.bluebrain.nexus.delta.sourcing.processor.{EventSourceProcessorConfig, StopStrategyConfig}
import pureconfig.ConfigReader

/**
  * Transient aggregate configuration.
  *
  * @param stopStrategy the stop strategy configuration for this aggregate [[EventDefinition]]
  * @param processor    the event source processor config
  */
final case class TransientAggregateConfig(
    stopStrategy: StopStrategyConfig,
    processor: EventSourceProcessorConfig
)

object TransientAggregateConfig {
  implicit final val transientAggregateConfigReader: ConfigReader[TransientAggregateConfig] =
    ConfigReader.fromCursor { cursor =>
      for {
        obj           <- cursor.asObjectCursor
        processor     <- ConfigReader[EventSourceProcessorConfig].from(cursor)
        stopStrategyK <- obj.atKey("stop-strategy")
        stopStrategy  <- ConfigReader[StopStrategyConfig].from(stopStrategyK)
      } yield TransientAggregateConfig(stopStrategy, processor)
    }
}
