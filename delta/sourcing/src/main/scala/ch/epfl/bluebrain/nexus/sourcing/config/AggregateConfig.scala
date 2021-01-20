package ch.epfl.bluebrain.nexus.sourcing.config

import akka.util.Timeout
import ch.epfl.bluebrain.nexus.sourcing.SnapshotStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.{EventSourceProcessorConfig, StopStrategyConfig}
import monix.execution.Scheduler
import pureconfig.ConfigReader

import scala.concurrent.duration.FiniteDuration

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

object AggregateConfig {
  implicit final val aggregateConfigReader: ConfigReader[AggregateConfig] =
    ConfigReader.fromCursor { cursor =>
      for {
        obj                   <- cursor.asObjectCursor
        atc                   <- obj.atKey("ask-timeout")
        askTimeout            <- ConfigReader[FiniteDuration].from(atc)
        emdc                  <- obj.atKey("evaluation-max-duration")
        evaluationMaxDuration <- ConfigReader[FiniteDuration].from(emdc)
        ssc                   <- obj.atKey("stash-size")
        stashSize             <- ssc.asInt
        stopStrategyK         <- obj.atKey("stop-strategy")
        stopStrategy          <- ConfigReader[StopStrategyConfig].from(stopStrategyK)
        snapshotStrategyK     <- obj.atKey("snapshot-strategy")
        snapshotStrategy      <- ConfigReader[SnapshotStrategyConfig].from(snapshotStrategyK)
      } yield AggregateConfig(
        stopStrategy,
        snapshotStrategy,
        EventSourceProcessorConfig(Timeout(askTimeout), evaluationMaxDuration, Scheduler.global, stashSize)
      )
    }
}
