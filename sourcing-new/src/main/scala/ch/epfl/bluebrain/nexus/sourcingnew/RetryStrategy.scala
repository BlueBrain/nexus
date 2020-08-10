package ch.epfl.bluebrain.nexus.sourcingnew

import cats.{Applicative, Monad}
import retry.RetryPolicies._
import retry.{RetryDetails, RetryPolicy}

import scala.concurrent.duration.FiniteDuration

final case class RetryStrategy[F[_]: Applicative](config: RetryStrategyConfig,
                               retryWhen: Throwable => Boolean,
                               onError: (Throwable, RetryDetails) => F[Unit]) {
  implicit val policy: RetryPolicy[F] = config.toPolicy[F]
  implicit def errorHandler: (Throwable, RetryDetails) => F[Unit] = onError
}

object RetryStrategy {

  def alwaysGiveUp[F[_]: Applicative: Monad]: RetryStrategy[F] =
    RetryStrategy(AlwaysGiveUp, _ => false, retry.noop[F, Throwable])

  def constant[F[_]: Applicative: Monad](constant: FiniteDuration,
                                         maxRetries: Int,
                                         retryWhen: Throwable => Boolean): RetryStrategy[F] =
    RetryStrategy(
      ConstantStrategyConfig(constant, maxRetries),
      retryWhen,
      retry.noop[F, Throwable]
    )

}

sealed trait RetryStrategyConfig {

  def toPolicy[F[_]: Applicative]: RetryPolicy[F]

}

case object AlwaysGiveUp extends RetryStrategyConfig {
  override def toPolicy[F[_]: Applicative]: RetryPolicy[F] = alwaysGiveUp
}

final case class ConstantStrategyConfig(constant: FiniteDuration,
                                        maxRetries: Int) extends RetryStrategyConfig {
  override def toPolicy[F[_]: Applicative]: RetryPolicy[F] =
    constantDelay[F](constant) join limitRetries[F](maxRetries)
}

final case class OnceStrategyConfig(constant: FiniteDuration) extends RetryStrategyConfig {
  override def toPolicy[F[_]: Applicative]: RetryPolicy[F] =
    constantDelay[F](constant) join limitRetries[F](1)
}

final case class ExponentialStrategyConfig(initialDelay: FiniteDuration,
                                           maxDelay: FiniteDuration,
                                           maxRetries: Int) extends RetryStrategyConfig {
  override def toPolicy[F[_]: Applicative]: RetryPolicy[F] =
    capDelay[F](maxDelay, fullJitter[F](initialDelay)) join limitRetries[F](maxRetries)
}
