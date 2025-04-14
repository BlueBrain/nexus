package ch.epfl.bluebrain.nexus.delta.kernel

import cats.effect.IO
import org.typelevel.log4cats.Logger
import pureconfig.ConfigReader
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure}
import pureconfig.generic.semiauto.*
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}
import retry.RetryPolicies.*
import retry.syntax.all.*
import retry.{RetryDetails, RetryPolicies, RetryPolicy}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
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
    onError: (E, RetryDetails) => IO[Unit]
) {
  val policy: RetryPolicy[IO[*]] = config.toPolicy[E]
}

object RetryStrategy {

  /**
    * Apply the provided strategy on the given io
    */
  def use[E: ClassTag, A](io: IO[A], retryStrategy: RetryStrategy[E]): IO[A] =
    io.retryingOnSomeErrors(
      {
        case error: E => IO.pure(retryStrategy.retryWhen(error))
        case _        => IO.pure(false)
      },
      retryStrategy.policy,
      (error, retryDetails) =>
        error match {
          case error: E => retryStrategy.onError(error, retryDetails)
          case _        => IO.unit
        }
    )

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
  def alwaysGiveUp[E](onError: (E, RetryDetails) => IO[Unit]): RetryStrategy[E] =
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
      onError: (E, RetryDetails) => IO[Unit]
  ): RetryStrategy[E] =
    RetryStrategy(
      RetryStrategyConfig.ConstantStrategyConfig(constant, maxRetries),
      retryWhen,
      onError
    )

  def retryOnNonFatal(
      config: RetryStrategyConfig,
      logger: Logger[IO],
      action: String
  ): RetryStrategy[Throwable] =
    RetryStrategy(
      config,
      (t: Throwable) => NonFatal(t),
      (t: Throwable, d: RetryDetails) => logError[Throwable](logger, action)(t, d)
    )

}

/**
  * Configuration for a [[RetryStrategy]]
  */
sealed trait RetryStrategyConfig extends Product with Serializable {
  def toPolicy[E]: RetryPolicy[IO[*]]

}

object RetryStrategyConfig {

  /**
    * Fails without retry
    */
  case object AlwaysGiveUp extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[IO[*]] = alwaysGiveUp[IO[*]]
  }

  /**
    * Retry at a constant interval
    * @param delay
    *   the interval before a retry will be attempted
    * @param maxRetries
    *   the maximum number of retries
    */
  final case class ConstantStrategyConfig(delay: FiniteDuration, maxRetries: Int) extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[IO[*]] =
      constantDelay[IO[*]](delay) join limitRetries(maxRetries)
  }

  /**
    * Retry exactly once
    * @param delay
    *   the interval before the retry will be attempted
    */
  final case class OnceStrategyConfig(delay: FiniteDuration) extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[IO[*]] =
      constantDelay[IO[*]](delay) join limitRetries(1)
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
    override def toPolicy[E]: RetryPolicy[IO[*]] =
      capDelay[IO[*]](maxDelay, fullJitter(initialDelay)) join limitRetries(maxRetries)
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
    override def toPolicy[E]: RetryPolicy[IO[*]] =
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
