package ch.epfl.bluebrain.nexus.delta.sourcing2.config

import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import monix.execution.Scheduler

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

final case class AggregateConfig(
    askTimeout: Timeout,
    evaluationMaxDuration: FiniteDuration,
    stashSize: Int,
    retryStrategy: RetryStrategyConfig
) {
  val evaluationExecutionContext: ExecutionContext = Scheduler.global
}
