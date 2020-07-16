package ch.epfl.bluebrain.nexus.util

import cats.effect.IO

import scala.reflect.ClassTag

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
  def passWhenErrorType[Ex <: Throwable](implicit Ex: ClassTag[Ex]): IO[Unit] =
    io.redeemWith(
      {
        case Ex(_) => IO.unit
        case th    => IO.raiseError(new RuntimeException("Received unexpected error", th))
      },
      _ => IO.raiseError(new RuntimeException(s"Expected error of type '${Ex.runtimeClass.getCanonicalName}'"))
    )
}
