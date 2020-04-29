package ch.epfl.bluebrain.nexus.rdf

import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import org.scalactic
import org.scalatest.exceptions.{StackDepthException, TestFailedException}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{EitherValues, Inspectors, OptionValues, TryValues}
import io.circe.syntax._

import scala.io.Source

trait RdfSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValues with OptionValues with TryValues {

  implicit def convertEitherToValuable[L, R](
      either: Either[L, R]
  )(implicit p: scalactic.source.Position): EitherValuable[L, R] =
    new EitherValuable(either, p)

  implicit def jsonKeysSyntax(json: Json): JsonKeysSyntax = new JsonKeysSyntax(json)

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

  final class JsonKeysSyntax(private val json: Json) {
    def removeKeys(keys: String*): Json       = removeTopKeys(json, keys: _*)
    def removeNestedKeys(keys: String*): Json = removeNestedKeysInner(json, keys)
  }

  def urlEncode(s: String): String = URLEncoder.encode(s, UTF_8.displayName()).replaceAll("\\+", "%20")

  final def jsonFiles(resourcePath: String, fileFilter: File => Boolean = _ => true): Map[String, Json] = {
    new File(getClass.getResource(resourcePath).getPath)
      .listFiles()
      .filter(fileFilter)
      .map(file => file.getName -> jsonContentOf(s"$resourcePath/${file.getName}"))
      .toMap
  }

  final def contentOf(resourcePath: String): String =
    Source.fromInputStream(getClass.getResourceAsStream(resourcePath)).mkString

  final def jsonContentOf(resourcePath: String): Json =
    parse(Source.fromInputStream(getClass.getResourceAsStream(resourcePath), UTF_8.name()).mkString)
      .getOrElse(throw new IllegalArgumentException(s"Exception fetching '$resourcePath'"))

  final def jsonWithViewContext(resourcePath: String): Json =
    jsonContentOf("/view-context.json") deepMerge jsonContentOf(resourcePath)

  private def removeEmpty(arr: Seq[Json]): Seq[Json] =
    arr.filter(j => j != Json.obj() && j != Json.fromString("") && j != Json.arr())

  def removeTopKeys(json: Json, keys: String*): Json = {
    def inner(obj: JsonObject): JsonObject = obj.filterKeys(!keys.contains(_))
    json.arrayOrObject[Json](
      json,
      arr => Json.fromValues(removeEmpty(arr.map(j => removeTopKeys(j, keys: _*)))),
      obj => inner(obj).asJson
    )
  }

  def removeNestedKeysInner(json: Json, keys: Seq[String]): Json = {
    def inner(obj: JsonObject): JsonObject =
      JsonObject.fromIterable(
        obj.filterKeys(!keys.contains(_)).toVector.map { case (k, v) => k -> removeNestedKeysInner(v, keys) }
      )
    json.arrayOrObject[Json](
      json,
      arr => Json.fromValues(removeEmpty(arr.map(j => removeNestedKeysInner(j, keys)))),
      obj => inner(obj).asJson
    )
  }
}

object RdfSpec extends RdfSpec
