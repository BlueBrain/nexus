package ch.epfl.bluebrain.nexus.util

import cats.effect.{IO, Timer}
import cats.implicits.catsSyntaxFlatMapOps
import org.scalatest.Assertion

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NonFatal

trait DistageSpecSugar {
  implicit final def circeJsonSyntax[A](io: IO[A]): DistageSpecOps[A] = new DistageSpecOps(io)
}
final class DistageSpecOps[A](private val io: IO[A]) extends AnyVal {

  /**
    * Redeem the IO when it raises an error with the passed value.
    */
  def passWhenError[Ex <: Throwable](value: => Ex): IO[Unit] =
    io.redeemWith(
      {
        case th if th == value => IO.unit
        case th                => IO.raiseError(new RuntimeException("Received unexpected error", th))
      },
      _ => IO.raiseError(new RuntimeException(s"Expected error '$value'"))
    )

  /**
    * Redeem the IO when it raises an error on the type ''Ex''.
    */
  def passWhenErrorType[Ex <: Throwable](implicit Ex: ClassTag[Ex]): IO[Ex] =
    io.redeemWith(
      {
        case Ex(value) => IO.pure(value)
        case th        => IO.raiseError(new RuntimeException("Received unexpected error", th))
      },
      _ => IO.raiseError(new RuntimeException(s"Expected error of type '${Ex.runtimeClass.getCanonicalName}'"))
    )

  /**
    * Retries the execution until it succeeds.
    *
    * @param f the assertion function
    * @param maxRetries the max number of retries
    * @param retryDelay the delay between retries
    */
  def eventually(f: A => Assertion, maxRetries: Int = 10, retryDelay: FiniteDuration = 500.milliseconds)(implicit
      T: Timer[IO]
  ): IO[Assertion] =
    io.flatMap { value =>
      IO(f(value)).redeemWith(
        {
          case NonFatal(th) =>
            if (maxRetries > 0) T.sleep(retryDelay) >> eventually(f, maxRetries - 1, retryDelay)
            else IO.raiseError(th)
        },
        assertion => IO.pure(assertion)
      )
    }
}
