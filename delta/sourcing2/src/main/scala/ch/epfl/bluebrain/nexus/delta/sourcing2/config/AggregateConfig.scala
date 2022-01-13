package ch.epfl.bluebrain.nexus.delta.sourcing2.config

import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing2.EntityDefinition.PersistentDefinition.StopStrategy
import monix.execution.Scheduler

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

final case class AggregateConfig(
    askTimeout: Timeout,
    evaluationMaxDuration: FiniteDuration,
    stashSize: Int,
    stopStrategy: StopStrategy,
    retryStrategy: RetryStrategyConfig
) {
  val evaluationExecutionContext: ExecutionContext = Scheduler.global
}
