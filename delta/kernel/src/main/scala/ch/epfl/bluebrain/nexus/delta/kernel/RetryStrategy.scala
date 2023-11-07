package ch.epfl.bluebrain.nexus.delta.kernel

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.toMonixBIOOps
import com.typesafe.scalalogging.{Logger => ScalaLoggingLogger}

import monix.bio.{IO => BIO, UIO}
import org.typelevel.log4cats.Logger
import pureconfig.ConfigReader
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure}
import pureconfig.generic.semiauto._
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}
import retry.RetryPolicies._
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicies, RetryPolicy}

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
  * Strategy to apply when an action fails
  * @param config
  *   the config which allows to define a cats-retry policy
  * @param retryWhen
  *   to decide whether a given error is worth retrying
  * @param onError
  *   an error handler
  */
final case class RetryStrategy[E](
    config: RetryStrategyConfig,
    retryWhen: E => Boolean,
    onError: (E, RetryDetails) => BIO[E, Unit]
) {
  val policy: RetryPolicy[BIO[E, *]] = config.toPolicy[E]
}

object RetryStrategy {

  /**
    * Apply the provided strategy on the given io
    */
  def use[E, A](io: BIO[E, A], retryStrategy: RetryStrategy[E]): BIO[E, A] =
    io.retryingOnSomeErrors(
      retryStrategy.retryWhen,
      retryStrategy.policy,
      retryStrategy.onError
    )

  /**
    * Log errors when retrying
    */
  def logError[E](logger: ScalaLoggingLogger, action: String): (E, RetryDetails) => BIO[E, Unit] = {
    case (err, WillDelayAndRetry(nextDelay, retriesSoFar, _)) =>
      val message = s"""Error $err while $action: retrying in ${nextDelay.toMillis}ms (retries so far: $retriesSoFar)"""
      UIO.delay(logger.warn(message))
    case (err, GivingUp(totalRetries, _))                     =>
      val message = s"""Error $err while $action, giving up (total retries: $totalRetries)"""
      UIO.delay(logger.error(message))
  }

  /**
    * Log errors when retrying
    */
  def logError[E](logger: org.typelevel.log4cats.Logger[IO], action: String): (E, RetryDetails) => IO[Unit] = {
    case (err, WillDelayAndRetry(nextDelay, retriesSoFar, _)) =>
      val message = s"""Error $err while $action: retrying in ${nextDelay.toMillis}ms (retries so far: $retriesSoFar)"""
      logger.warn(message)
    case (err, GivingUp(totalRetries, _))                     =>
      val message = s"""Error $err while $action, giving up (total retries: $totalRetries)"""
      logger.error(message)
  }

  /**
    * Fail without retry
    * @param onError
    *   what action to perform on error
    */
  def alwaysGiveUp[E](onError: (E, RetryDetails) => BIO[E, Unit]): RetryStrategy[E] =
    RetryStrategy(RetryStrategyConfig.AlwaysGiveUp, _ => false, onError)

  /**
    * Retry at a constant interval
    * @param constant
    *   the interval before a retry will be attempted
    * @param maxRetries
    *   the maximum number of retries
    * @param retryWhen
    *   the errors we are willing to retry for
    * @param onError
    *   what action to perform on error
    */
  def constant[E](
      constant: FiniteDuration,
      maxRetries: Int,
      retryWhen: E => Boolean,
      onError: (E, RetryDetails) => BIO[E, Unit]
  ): RetryStrategy[E] =
    RetryStrategy(
      RetryStrategyConfig.ConstantStrategyConfig(constant, maxRetries),
      retryWhen,
      onError
    )

  /**
    * Retry strategy which retries on all non fatal errors and just outputs a log when an error occurs
    *
    * @param config
    *   the retry configuration
    * @param logger
    *   the logger to use
    * @param action
    *   the action that was performed
    */
  def retryOnNonFatal(
      config: RetryStrategyConfig,
      logger: ScalaLoggingLogger,
      action: String
  ): RetryStrategy[Throwable] =
    RetryStrategy(
      config,
      (t: Throwable) => NonFatal(t),
      (t: Throwable, d: RetryDetails) => logError(logger, action)(t, d)
    )

  def retryOnNonFatal(
      config: RetryStrategyConfig,
      logger: Logger[IO],
      action: String
  ): RetryStrategy[Throwable] =
    RetryStrategy(
      config,
      (t: Throwable) => NonFatal(t),
      (t: Throwable, d: RetryDetails) => logError[Throwable](logger, action)(t, d).toBIOThrowable
    )

}

/**
  * Configuration for a [[RetryStrategy]]
  */
sealed trait RetryStrategyConfig extends Product with Serializable {
  def toPolicy[E]: RetryPolicy[BIO[E, *]]

}

object RetryStrategyConfig {

  /**
    * Fails without retry
    */
  case object AlwaysGiveUp extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[BIO[E, *]] = alwaysGiveUp[BIO[E, *]]
  }

  /**
    * Retry at a constant interval
    * @param delay
    *   the interval before a retry will be attempted
    * @param maxRetries
    *   the maximum number of retries
    */
  final case class ConstantStrategyConfig(delay: FiniteDuration, maxRetries: Int) extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[BIO[E, *]] =
      constantDelay[BIO[E, *]](delay) join limitRetries(maxRetries)
  }

  /**
    * Retry exactly once
    * @param delay
    *   the interval before the retry will be attempted
    */
  final case class OnceStrategyConfig(delay: FiniteDuration) extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[BIO[E, *]] =
      constantDelay[BIO[E, *]](delay) join limitRetries(1)
  }

  /**
    * Retry with an exponential delay after a failure
    * @param initialDelay
    *   the initial delay after the first failure
    * @param maxDelay
    *   the maximum delay to not exceed
    * @param maxRetries
    *   the maximum number of retries
    */
  final case class ExponentialStrategyConfig(initialDelay: FiniteDuration, maxDelay: FiniteDuration, maxRetries: Int)
      extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[BIO[E, *]] =
      capDelay[BIO[E, *]](maxDelay, fullJitter(initialDelay)) join limitRetries(maxRetries)
  }

  /**
    * Retry with a constant delay until the total delay reaches the limit
    * @param threshold
    *   the maximum cumulative delay
    * @param delay
    *   the delay between each try
    */
  final case class MaximumCumulativeDelayConfig(threshold: FiniteDuration, delay: FiniteDuration)
      extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[BIO[E, *]] =
      RetryPolicies.limitRetriesByCumulativeDelay(
        threshold,
        RetryPolicies.constantDelay(delay)
      )
  }

  implicit val retryStrategyConfigReader: ConfigReader[RetryStrategyConfig] = {
    val onceRetryStrategy: ConfigReader[OnceStrategyConfig]                        = deriveReader[OnceStrategyConfig]
    val constantRetryStrategy: ConfigReader[ConstantStrategyConfig]                = deriveReader[ConstantStrategyConfig]
    val exponentialRetryStrategy: ConfigReader[ExponentialStrategyConfig]          = deriveReader[ExponentialStrategyConfig]
    val maximumCumulativeDelayStrategy: ConfigReader[MaximumCumulativeDelayConfig] =
      deriveReader[MaximumCumulativeDelayConfig]

    ConfigReader.fromCursor { cursor =>
      for {
        obj      <- cursor.asObjectCursor
        rc       <- obj.atKey("retry")
        retry    <- ConfigReader[String].from(rc)
        strategy <- retry match {
                      case "never"         => Right(AlwaysGiveUp)
                      case "once"          => onceRetryStrategy.from(obj)
                      case "constant"      => constantRetryStrategy.from(obj)
                      case "exponential"   => exponentialRetryStrategy.from(obj)
                      case "maximum-delay" => maximumCumulativeDelayStrategy.from(obj)
                      case other           =>
                        Left(
                          ConfigReaderFailures(
                            ConvertFailure(
                              CannotConvert(
                                other,
                                "string",
                                "'retry' value must be one of ('never', 'once', 'constant', 'exponential')"
                              ),
                              obj
                            )
                          )
                        )
                    }
      } yield strategy
    }
  }
}
