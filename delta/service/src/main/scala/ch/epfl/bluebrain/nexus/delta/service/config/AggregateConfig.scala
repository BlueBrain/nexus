package ch.epfl.bluebrain.nexus.delta.service.config

import akka.util.Timeout
import ch.epfl.bluebrain.nexus.sourcing.SnapshotStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.{AggregateProcessorConfig, StopStrategyConfig}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * Aggregate configuration.
  *
  * @param stopStrategy               the stop strategy configuration for this aggregate [[EventDefinition]]
  * @param snapshotStrategy           the snapshot strategy configuration for this aggregate [[EventDefinition]]
  * @param askTimeout                 timeout for the message exchange with the aggregate actor
  * @param evaluationMaxDuration      timeout for evaluating commands
  * @param evaluationExecutionContext the execution context where commands are to be evaluated
  * @param stashSize                  the maximum size allowed for stashing when evaluating
  */
final case class AggregateConfig(
    stopStrategy: StopStrategyConfig,
    snapshotStrategy: SnapshotStrategyConfig,
    askTimeout: Timeout,
    evaluationMaxDuration: FiniteDuration,
    evaluationExecutionContext: ExecutionContext,
    stashSize: Int
) {

  /**
    * The underlying processor ''AggregateConfig''
    */
  def processor: AggregateProcessorConfig =
    AggregateProcessorConfig(askTimeout, evaluationMaxDuration, evaluationExecutionContext, stashSize)
}
