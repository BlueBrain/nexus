package ch.epfl.bluebrain.nexus.testkit.scalatest.ce

import cats.effect.IO

import scala.concurrent.duration.DurationInt

trait CatsIOValues {

  implicit final class CatsIOValuesOps[A](private val io: IO[A]) {
    def accepted: A =
      io.unsafeRunTimed(45.seconds).getOrElse(throw new RuntimeException("IO timed out during .accepted call"))
  }
}
