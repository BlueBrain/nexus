package ch.epfl.bluebrain.nexus.sourcing

import cats.Applicative
import retry.RetryPolicies.{alwaysGiveUp, capDelay, constantDelay, fullJitter, limitRetries}
import retry.RetryPolicy

import scala.concurrent.duration.FiniteDuration

/**
  * Retry strategy configuration.
  *
  * @param strategy     the type of strategy; possible options are "never", "once", "constant" and "exponential"
  * @param initialDelay the initial delay before retrying that will be multiplied with the 'factor' for each attempt
  *                     (applicable only for strategy "exponential")
  * @param maxDelay     the maximum delay (applicable for strategy "exponential")
  * @param maxRetries   maximum number of retries in case of failure (applicable for strategy "exponential" and "constant")
  * @param constant    the constant delay (applicable only for strategy "constant")
  */
final case class RetryStrategyConfig(
    strategy: String,
    initialDelay: FiniteDuration,
    maxDelay: FiniteDuration,
    maxRetries: Int,
    constant: FiniteDuration
) {

  /**
    * Computes a retry policy from the provided configuration.
    */
  def retryPolicy[F[_]: Applicative]: RetryPolicy[F] =
    strategy match {
      case "exponential" => capDelay[F](maxDelay, fullJitter[F](initialDelay)) join limitRetries[F](maxRetries)
      case "constant"    => constantDelay[F](constant) join limitRetries[F](maxRetries)
      case "once"        => constantDelay[F](initialDelay) join limitRetries[F](1)
      case _             => alwaysGiveUp
    }
}
