package ch.epfl.bluebrain.nexus.sourcing

import monix.bio.Task
import retry.RetryPolicies._
import retry.{RetryDetails, RetryPolicy}
import com.typesafe.scalalogging.Logger
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

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

  /**
    * Log errors when retrying
    */
  def logError(logger: Logger, action: String): (Throwable, RetryDetails) => Task[Unit] = {
    case (err, WillDelayAndRetry(nextDelay, retriesSoFar, _)) =>
      Task.delay(logger.warn(s"""Error occurred while $action:
                         |
                         |${err.getMessage}
                         |
                         |Will retry in ${nextDelay.toMillis}ms ... (retries so far: $retriesSoFar)""".stripMargin))
    case (err, GivingUp(totalRetries, _))                     =>
      Task.delay(logger.warn(s"""Error occurred while $action:
                         |
                         |${err.getMessage}
                         |
                         |Giving up ... (total retries: $totalRetries)""".stripMargin))

  }

  /**
    * Fail without retry
    */
  def alwaysGiveUp: RetryStrategy =
    RetryStrategy(RetryStrategyConfig.AlwaysGiveUp, _ => false, retry.noop[Task, Throwable])

  /**
    * Retry at a constant interval
    * @param constant the interval before a retry will be attempted
    * @param maxRetries the maximum number of retries
    * @param retryWhen the errors we are willing to retry for
    */
  def constant(constant: FiniteDuration, maxRetries: Int, retryWhen: Throwable => Boolean): RetryStrategy =
    RetryStrategy(
      RetryStrategyConfig.ConstantStrategyConfig(constant, maxRetries),
      retryWhen,
      retry.noop[Task, Throwable]
    )

  /**
    * Retry strategy which retries on all non fatal errors and just outputs a log
    * when an error occurs
    *
   * @param config the retry configuration
    * @param logger the logger to use
    */
  def retryOnNonFatal(config: RetryStrategyConfig, logger: Logger): RetryStrategy =
    RetryStrategy(
      config,
      (t: Throwable) => NonFatal(t),
      (t: Throwable, d: RetryDetails) =>
        Task {
          logger.error(s"Retrying after the following error with details $d", t)
        }
    )

}

/**
  * Configuration for a [[RetryStrategy]]
  */
sealed trait RetryStrategyConfig extends Product with Serializable {
  def toPolicy: RetryPolicy[Task]

}

object RetryStrategyConfig {

  /**
    * Fails without retry
    */
  case object AlwaysGiveUp extends RetryStrategyConfig {
    override def toPolicy: RetryPolicy[Task] = alwaysGiveUp[Task]
  }

  /**
    * Retry at a constant interval
    * @param constant the interval before a retry will be attempted
    * @param maxRetries the maximum number of retries
    */
  final case class ConstantStrategyConfig(constant: FiniteDuration, maxRetries: Int) extends RetryStrategyConfig {
    override def toPolicy: RetryPolicy[Task] =
      constantDelay[Task](constant) join limitRetries(maxRetries)
  }

  /**
    * Retry exactly once
    * @param constant the interval before the retry will be attempted
    */
  final case class OnceStrategyConfig(constant: FiniteDuration) extends RetryStrategyConfig {
    override def toPolicy: RetryPolicy[Task] =
      constantDelay[Task](constant) join limitRetries(1)
  }

  /**
    * Retry with an exponential delay after a failure
    * @param initialDelay the initial delay after the first failure
    * @param maxDelay     the maximum delay to not exceed
    * @param maxRetries   the maximum number of retries
    */
  final case class ExponentialStrategyConfig(initialDelay: FiniteDuration, maxDelay: FiniteDuration, maxRetries: Int)
      extends RetryStrategyConfig {
    override def toPolicy: RetryPolicy[Task] =
      capDelay[Task](maxDelay, fullJitter(initialDelay)) join limitRetries(maxRetries)
  }

}
