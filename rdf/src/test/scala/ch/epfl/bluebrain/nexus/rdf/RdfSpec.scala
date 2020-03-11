package ch.epfl.bluebrain.nexus.rdf

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

import io.circe.Json
import io.circe.parser.parse
import org.scalactic
import org.scalatest.exceptions.{StackDepthException, TestFailedException}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{EitherValues, Inspectors, OptionValues, TryValues}

import scala.io.Source

trait RdfSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValues with OptionValues with TryValues {

  implicit def convertEitherToValuable[L, R](
      either: Either[L, R]
  )(implicit p: scalactic.source.Position): EitherValuable[L, R] =
    new EitherValuable(either, p)

  final class EitherValuable[L, R](either: Either[L, R], pos: scalactic.source.Position) {
    def rightValue: R = either match {
      case Right(value) => value
      case Left(th: Throwable) =>
        throw new TestFailedException(
          (_: StackDepthException) => Some("The Either value is not a Right(_)"),
          Some(th),
          pos
        )
      case Left(_) =>
        throw new TestFailedException((_: StackDepthException) => Some("The Either value is not a Right(_)"), None, pos)
    }

    def leftValue: L = either match {
      case Left(value) => value
      case Right(_) =>
        throw new TestFailedException((_: StackDepthException) => Some("The Either value is not a Left(_)"), None, pos)
    }
  }

  def urlEncode(s: String): String = URLEncoder.encode(s, UTF_8.displayName()).replaceAll("\\+", "%20")

  final def jsonContentOf(resourcePath: String): Json =
    parse(Source.fromInputStream(getClass.getResourceAsStream(resourcePath)).mkString)
      .getOrElse(throw new IllegalArgumentException)

  final def jsonWithViewContext(resourcePath: String): Json =
    jsonContentOf("/view-context.json") deepMerge jsonContentOf(resourcePath)
}
