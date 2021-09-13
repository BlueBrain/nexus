package ch.epfl.bluebrain.nexus.delta.sourcing.processor

import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import monix.execution.Scheduler
import pureconfig.ConfigReader
import pureconfig.error.FailureReason
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn
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
  */
final case class EventSourceProcessorConfig(
    askTimeout: Timeout,
    evaluationMaxDuration: FiniteDuration,
    stashSize: Int,
    retryStrategy: RetryStrategyConfig
) {
  val evaluationExecutionContext: ExecutionContext = Scheduler.global
}

object EventSourceProcessorConfig {
  final private case class InvalidTimeouts(askTimeout: FiniteDuration, evaluationMaxDuration: FiniteDuration)
      extends FailureReason {
    val description: String =
      s"'ask-timeout' value must be greater than 'evaluation-max-duration'. However '$askTimeout' is not greater than '$evaluationMaxDuration'"
  }

  @nowarn("cat=unused")
  implicit private def timeoutConfigReader(implicit duration: ConfigReader[FiniteDuration]): ConfigReader[Timeout] =
    duration.map(Timeout(_))

  implicit final val eventSourceProcessorConfigReader: ConfigReader[EventSourceProcessorConfig] =
    deriveReader[EventSourceProcessorConfig].emap { c =>
      Either.cond(
        c.askTimeout.duration gt c.evaluationMaxDuration,
        c,
        InvalidTimeouts(c.askTimeout.duration, c.evaluationMaxDuration)
      )
    }
}
