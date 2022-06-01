package ch.epfl.bluebrain.nexus.delta.sourcing

import munit.{Assertions, Location}

trait EitherAssertions { self: Assertions =>

  implicit class EitherAssertionsOps[E, A](either: Either[E, A]) {

    def assertLeft(expected: E)(implicit loc: Location): Unit =
      either match {
        case Left(l)  => assertEquals(l, expected)
        case Right(r) => fail(s"Right caught: $r, expected as left: $expected")
      }

    def assertLeft(implicit loc: Location): Unit = {
      assert(either.isLeft, s"Right caught, expected a left")
    }

    def assertRight(expected: A)(implicit loc: Location): Unit =
      either match {
        case Left(l)  => fail(s"Left caught: $l, expected as right: $expected")
        case Right(r) => assertEquals(r, expected)
      }

  }

}
