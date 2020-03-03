package ch.epfl.bluebrain.nexus.cli.config

import cats.Applicative
import ch.epfl.bluebrain.nexus.cli.ClientRetryCondition
import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert
import retry.RetryPolicies._
import retry.RetryPolicy

import scala.concurrent.duration.FiniteDuration

/**
  * Retry strategy configuration.
  *
  * @param strategy     the type of strategy; possible options are "never", "once", "constant" and "exponential"
  * @param initialDelay the initial delay before retrying that will be multiplied with the 'factor' for each attempt
  *                     (applicable for strategy "exponential", "constant" and "once").
  *                     For "constant" strategy the value is both the initial delay and all the subsequent retries delay
  * @param maxDelay     the maximum delay (applicable for strategy "exponential")
  * @param maxRetries   maximum number of retries in case of failure (applicable for strategy "exponential" and "constant")
  * @param condition    the condition when is worth retrying; possible options are "never", "onServerError", "always".
  *                     Defaults to "never"
  */
// $COVERAGE-OFF$
final case class RetryStrategyConfig(
    strategy: String,
    initialDelay: FiniteDuration,
    maxDelay: FiniteDuration,
    maxRetries: Int,
    condition: String
) {

  /**
    * Computes a retry policy from the provided configuration.
    */
  def retryPolicy[F[_]: Applicative]: RetryPolicy[F] =
    strategy match {
      case "exponential" => capDelay[F](maxDelay, fullJitter[F](initialDelay)) join limitRetries[F](maxRetries)
      case "constant"    => constantDelay[F](initialDelay) join limitRetries[F](maxRetries)
      case "once"        => constantDelay[F](initialDelay) join limitRetries[F](1)
      case _             => alwaysGiveUp
    }

  val retryCondition: ClientRetryCondition = condition match {
    case "always"        => ClientRetryCondition.always
    case "onServerError" => ClientRetryCondition.onServerError
    case _               => ClientRetryCondition.never
  }
}
// $COVERAGE-ON$

object RetryStrategyConfig {
  implicit val retryStrategyConfigConvert: ConfigConvert[RetryStrategyConfig] =
    deriveConvert[RetryStrategyConfig]
}
