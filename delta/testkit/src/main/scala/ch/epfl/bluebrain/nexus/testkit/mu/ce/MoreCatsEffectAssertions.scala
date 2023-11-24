package ch.epfl.bluebrain.nexus.testkit.mu.ce

import cats.effect.IO
import munit.CatsEffectAssertions

import scala.reflect.ClassTag

trait MoreCatsEffectAssertions { self: CatsEffectAssertions =>
  implicit class MoreCatsEffectAssertionsOps[A](io: IO[A]) {
    def interceptEquals[E <: Throwable: ClassTag](expected: E): IO[Unit] = io.intercept[E].assertEquals(expected)
  }
}
