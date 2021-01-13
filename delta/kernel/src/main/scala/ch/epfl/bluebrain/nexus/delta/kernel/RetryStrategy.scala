package ch.epfl.bluebrain.nexus.delta.kernel

import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task}
import pureconfig.ConfigReader
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure}
import pureconfig.generic.semiauto._
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}
import retry.RetryPolicies._
import retry.{RetryDetails, RetryPolicy}

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
  * Strategy to apply when an action fails
  * @param config the config which allows to define a cats-retry policy
  * @param retryWhen to decide whether a given error is worth retrying
  * @param onError an error handler
  */
final case class RetryStrategy[E](
    config: RetryStrategyConfig,
    retryWhen: E => Boolean,
    onError: (E, RetryDetails) => IO[E, Unit]
) {
  implicit val policy: RetryPolicy[IO[E, *]]                  = config.toPolicy[E]
  implicit def errorHandler: (E, RetryDetails) => IO[E, Unit] = onError
}

object RetryStrategy {

  /**
    * Log errors when retrying
    */
  def logError[E](logger: Logger, action: String): (E, RetryDetails) => IO[E, Unit] = {
    case (err, WillDelayAndRetry(nextDelay, retriesSoFar, _)) =>
      IO.pure(logger.warn(s"""Error occurred while $action:
                         |
                         |$err
                         |
                         |Will retry in ${nextDelay.toMillis}ms ... (retries so far: $retriesSoFar)""".stripMargin))
    case (err, GivingUp(totalRetries, _))                     =>
      IO.pure(logger.warn(s"""Error occurred while $action:
                         |
                         |$err
                         |
                         |Giving up ... (total retries: $totalRetries)""".stripMargin))

  }

  /**
    * Fail without retry
    */
  def alwaysGiveUp[E]: RetryStrategy[E] =
    RetryStrategy(RetryStrategyConfig.AlwaysGiveUp, _ => false, retry.noop[IO[E, *], E])

  /**
    * Retry at a constant interval
    * @param constant the interval before a retry will be attempted
    * @param maxRetries the maximum number of retries
    * @param retryWhen the errors we are willing to retry for
    */
  def constant[E](constant: FiniteDuration, maxRetries: Int, retryWhen: E => Boolean): RetryStrategy[E] =
    RetryStrategy(
      RetryStrategyConfig.ConstantStrategyConfig(constant, maxRetries),
      retryWhen,
      retry.noop[IO[E, *], E]
    )

  /**
    * Retry strategy which retries on all non fatal errors and just outputs a log
    * when an error occurs
    *
    * @param config the retry configuration
    * @param logger the logger to use
    */
  def retryOnNonFatal(config: RetryStrategyConfig, logger: Logger): RetryStrategy[Throwable] =
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
  def toPolicy[E]: RetryPolicy[IO[E, *]]

}

object RetryStrategyConfig {

  /**
    * Fails without retry
    */
  case object AlwaysGiveUp extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[IO[E, *]] = alwaysGiveUp[IO[E, *]]
  }

  /**
    * Retry at a constant interval
    * @param constant the interval before a retry will be attempted
    * @param maxRetries the maximum number of retries
    */
  final case class ConstantStrategyConfig(constant: FiniteDuration, maxRetries: Int) extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[IO[E, *]] =
      constantDelay[IO[E, *]](constant) join limitRetries(maxRetries)
  }

  /**
    * Retry exactly once
    * @param constant the interval before the retry will be attempted
    */
  final case class OnceStrategyConfig(constant: FiniteDuration) extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[IO[E, *]] =
      constantDelay[IO[E, *]](constant) join limitRetries(1)
  }

  /**
    * Retry with an exponential delay after a failure
    * @param initialDelay the initial delay after the first failure
    * @param maxDelay     the maximum delay to not exceed
    * @param maxRetries   the maximum number of retries
    */
  final case class ExponentialStrategyConfig(initialDelay: FiniteDuration, maxDelay: FiniteDuration, maxRetries: Int)
      extends RetryStrategyConfig {
    override def toPolicy[E]: RetryPolicy[IO[E, *]] =
      capDelay[IO[E, *]](maxDelay, fullJitter(initialDelay)) join limitRetries(maxRetries)
  }

  implicit val retryStrategyConfigReader: ConfigReader[RetryStrategyConfig] = {
    val onceRetryStrategy: ConfigReader[OnceStrategyConfig]               = deriveReader[OnceStrategyConfig]
    val constantRetryStrategy: ConfigReader[ConstantStrategyConfig]       = deriveReader[ConstantStrategyConfig]
    val exponentialRetryStrategy: ConfigReader[ExponentialStrategyConfig] = deriveReader[ExponentialStrategyConfig]

    ConfigReader.fromCursor { cursor =>
      for {
        obj      <- cursor.asObjectCursor
        rc       <- obj.atKey("retry")
        retry    <- ConfigReader[String].from(rc)
        strategy <- retry match {
                      case "never"       => Right(AlwaysGiveUp)
                      case "once"        => onceRetryStrategy.from(obj)
                      case "constant"    => constantRetryStrategy.from(obj)
                      case "exponential" => exponentialRetryStrategy.from(obj)
                      case other         =>
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
