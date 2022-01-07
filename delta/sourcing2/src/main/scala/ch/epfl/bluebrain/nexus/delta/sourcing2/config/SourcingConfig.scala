package ch.epfl.bluebrain.nexus.delta.sourcing2.config

import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing2.track.TrackConfig
import monix.execution.Scheduler

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * Event source processor configuration.
  *
  * @param askTimeout
  *   timeout for the message exchange with the aggregate actor
  * @param evaluationMaxDuration
  *   timeout for evaluating commands
  * @param stashSize
  *   the maximum size allowed for stashing when evaluating
  * @param trackConfig
  *   the configuration for tracking
  * @param deltaVersion
  *   the Delta version
  */
final case class SourcingConfig(
    askTimeout: Timeout,
    evaluationMaxDuration: FiniteDuration,
    stashSize: Int,
    trackConfig: TrackConfig,
    retryStrategy: RetryStrategyConfig,
    deltaVersion: String
) {
  val evaluationExecutionContext: ExecutionContext = Scheduler.global
}
