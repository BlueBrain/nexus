package ch.epfl.bluebrain.nexus.testkit.mu.ce

import cats.effect.IO
import munit.Assertions.fail
import munit.Suite

import scala.concurrent.duration.DurationInt

trait CatsIOValues {

  self: Suite =>

  implicit final class CatsIOValuesOps[A](private val io: IO[A]) {
    def accepted: A =
      io.unsafeRunTimed(45.seconds).getOrElse(fail("IO timed out during .accepted call"))
  }
}
