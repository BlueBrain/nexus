package ch.epfl.bluebrain.nexus.sourcing.processor

import akka.util.Timeout

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * Event source processor configuration.
  *
  * @param askTimeout                 timeout for the message exchange with the aggregate actor
  * @param evaluationMaxDuration      timeout for evaluating commands
  * @param evaluationExecutionContext the execution context where commands are to be evaluated
  * @param stashSize                  the maximum size allowed for stashing when evaluating
  */
final case class EventSourceProcessorConfig(
    askTimeout: Timeout,
    evaluationMaxDuration: FiniteDuration,
    evaluationExecutionContext: ExecutionContext,
    stashSize: Int
)
