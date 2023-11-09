package ch.epfl.bluebrain.nexus.testkit.mu.ce

import cats.effect.IO
import munit.Assertions.fail
import munit.{Location, Suite}

import scala.concurrent.duration.DurationInt

trait CatsIOValues {

  self: Suite =>

  implicit final class CatsIOValuesOps[A](private val io: IO[A]) {
    def accepted(implicit loc: Location): A =
      io.attempt.unsafeRunTimed(45.seconds) match {
        case Some(Right(value)) => value
        case Some(Left(error))  => fail(s"IO failed with error '$error'")
        case None               => fail("IO timed out during .accepted call")
      }

    def failed(implicit loc: Location): Throwable =
      io.attempt.unsafeRunTimed(45.seconds) match {
        case Some(Right(value)) => fail(s"IO succeeded with value '$value'")
        case Some(Left(error))  => error
        case None               => fail("IO timed out during .failed call")
      }
  }
}
