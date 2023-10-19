package ch.epfl.bluebrain.nexus.testkit.mu

import munit.Assertions.fail
import munit.Suite

trait EitherValuesMUnit {
  self: Suite =>

  implicit class EitherValuesMUnitOps[L, R](either: Either[L, R]) {
    def rightValue(implicit loc: munit.Location): R = either match {
      case Right(value) => value
      case Left(value)  => fail(s"Expected Right but got Left($value)")
    }

    def leftValue(implicit loc: munit.Location): L = either match {
      case Left(value) => value
      case Right(_)    => fail("Expected Left but got Right($value)")
    }
  }
}
