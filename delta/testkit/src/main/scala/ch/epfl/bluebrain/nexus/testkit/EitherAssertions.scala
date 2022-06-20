package ch.epfl.bluebrain.nexus.testkit

import munit.{Assertions, Location}

trait EitherAssertions { self: Assertions =>

  implicit class EitherAssertionsOps[E, A](either: Either[E, A])(implicit loc: Location) {

    def assertLeft(expected: E): Unit =
      either match {
        case Left(l)  => assertEquals(l, expected)
        case Right(r) => fail(s"Right caught: $r, expected as left: $expected")
      }

    def assertLeft(): Unit = {
      assert(either.isLeft, s"Right caught, expected a left")
    }

    def assertRight(expected: A): Unit =
      either match {
        case Left(l)  => fail(s"Left caught: $l, expected as right: $expected")
        case Right(r) => assertEquals(r, expected)
      }

    def rightValue: A =
      either match {
        case Right(r) => r
        case Left(l)  => fail(s"Left caught: $l, expected a right value.")
      }

  }

}
