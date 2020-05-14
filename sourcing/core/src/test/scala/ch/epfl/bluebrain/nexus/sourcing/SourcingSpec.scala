package ch.epfl.bluebrain.nexus.sourcing

import org.scalactic.source
import org.scalatest.{Inspectors, OptionValues, TryValues}
import org.scalatest.exceptions.{StackDepthException, TestFailedException}
import org.scalatest.matchers.should.Matchers

trait SourcingSpec
    extends org.scalatest.wordspec.AnyWordSpecLike
    with Matchers
    with Inspectors
    with OptionValues
    with TryValues {

  class EitherValuable[L, R](either: Either[L, R], pos: source.Position) {
    def rightValue: R = either match {
      case Right(value) => value
      case Left(_) =>
        throw new TestFailedException((_: StackDepthException) => Some("The Either value is not a Right(_)"), None, pos)
    }

    def leftValue: L = either match {
      case Left(value) => value
      case Right(_) =>
        throw new TestFailedException((_: StackDepthException) => Some("The Either value is not a Left(_)"), None, pos)
    }
  }

  implicit def convertEitherToValuable[L, R](either: Either[L, R])(implicit p: source.Position): EitherValuable[L, R] =
    new EitherValuable(either, p)

}
