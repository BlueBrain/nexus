package ch.epfl.bluebrain.nexus.testkit.mu

import munit.{Assertions, Location}

import scala.reflect.ClassTag

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

    def assertLeftOf[T](implicit T: ClassTag[T]): Unit = {
      either match {
        case Left(T(_)) => ()
        case Left(l)    =>
          fail(s"Wrong left type caught, expected : '${T.runtimeClass.getName}', actual: '${l.getClass.getName}'")
        case Right(_)   => fail(s"Right caught, expected a left")
      }
    }

    def assertRight(expected: A): Unit =
      either match {
        case Left(l)  => fail(s"Left caught: $l, expected as right: $expected")
        case Right(r) => assertEquals(r, expected)
      }
  }

}
