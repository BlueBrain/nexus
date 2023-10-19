package ch.epfl.bluebrain.nexus.testkit.ce

import cats.effect.IO

trait CatsIOValues {

  implicit final class CatsIOValuesOps[A](private val io: IO[A]) {
    def accepted: A = io.unsafeRunSync()
  }
}
