package ch.epfl.bluebrain.nexus.testkit

trait EitherValuesMUnit {
  self: munit.Suite =>

  implicit class EitherValuesMUnitOps[L, R](either: Either[L, R]) {
    def rightValue(implicit loc: munit.Location): R = either match {
      case Right(value) => value
      case Left(value)  => munit.Assertions.fail(s"Expected Right but got Left($value)")
    }

    def leftValue(implicit loc: munit.Location): L = either match {
      case Left(value) => value
      case Right(_)    => munit.Assertions.fail("Expected Left but got Right($value)")
    }
  }
}
