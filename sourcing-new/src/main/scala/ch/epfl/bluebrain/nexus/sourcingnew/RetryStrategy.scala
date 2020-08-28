package ch.epfl.bluebrain.nexus.sourcingnew

import monix.bio.Task
import retry.RetryPolicies._
import retry.{RetryDetails, RetryPolicy}

import scala.concurrent.duration.FiniteDuration

/**
  * Strategy to apply when an action fails
  * @param config the config which allows to define a cats-retry policy
  * @param retryWhen to decide whether a given error is worth retrying
  * @param onError an error handler
  */
final case class RetryStrategy(
    config: RetryStrategyConfig,
    retryWhen: Throwable => Boolean,
    onError: (Throwable, RetryDetails) => Task[Unit]
) {
  implicit val policy: RetryPolicy[Task]                             = config.toPolicy
  implicit def errorHandler: (Throwable, RetryDetails) => Task[Unit] = onError
}

object RetryStrategy {

  def alwaysGiveUp: RetryStrategy =
    RetryStrategy(AlwaysGiveUp, _ => false, retry.noop[Task, Throwable])

  def constant(constant: FiniteDuration, maxRetries: Int, retryWhen: Throwable => Boolean): RetryStrategy =
    RetryStrategy(
      ConstantStrategyConfig(constant, maxRetries),
      retryWhen,
      retry.noop[Task, Throwable]
    )

}

sealed trait RetryStrategyConfig {

  def toPolicy: RetryPolicy[Task]

}

case object AlwaysGiveUp extends RetryStrategyConfig {
  override def toPolicy: RetryPolicy[Task] = alwaysGiveUp[Task]
}

final case class ConstantStrategyConfig(constant: FiniteDuration, maxRetries: Int) extends RetryStrategyConfig {
  override def toPolicy: RetryPolicy[Task] =
    constantDelay[Task](constant) join limitRetries(maxRetries)
}

final case class OnceStrategyConfig(constant: FiniteDuration) extends RetryStrategyConfig {
  override def toPolicy: RetryPolicy[Task] =
    constantDelay[Task](constant) join limitRetries(1)
}

final case class ExponentialStrategyConfig(initialDelay: FiniteDuration, maxDelay: FiniteDuration, maxRetries: Int)
    extends RetryStrategyConfig {
  override def toPolicy: RetryPolicy[Task] =
    capDelay[Task](maxDelay, fullJitter(initialDelay)) join limitRetries(maxRetries)
}
