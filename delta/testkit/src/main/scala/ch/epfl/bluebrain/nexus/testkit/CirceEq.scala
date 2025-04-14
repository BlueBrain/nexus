package ch.epfl.bluebrain.nexus.testkit

import cats.Eq
import cats.implicits.catsSyntaxEq
import ch.epfl.bluebrain.nexus.testkit.CirceEq.{ignoreJsonKeyOrderEq, IgnoredArrayOrder}
import io.circe.*
import io.circe.syntax.*
import org.scalatest.matchers.*

trait CirceEq {
  def equalIgnoreArrayOrder(json: Json): IgnoredArrayOrder = IgnoredArrayOrder(json)

  def field(fieldName: String, expectedValue: Json): HavePropertyMatcher[Json, Json] = HavePropertyMatcher(left => {
    val actualValue = left.hcursor.downField(fieldName).as[Json].getOrElse(Json.Null)
    HavePropertyMatchResult(
      actualValue == expectedValue,
      fieldName,
      expectedValue,
      actualValue
    )
  })

  def arrayThatContains(expectedValue: Json): BeMatcher[Json] = (left: Json) => {
    implicit val jsonEq: Eq[Json] = ignoreJsonKeyOrderEq
    MatchResult(
      left.asArray.exists(arr => arr.exists(_ === expectedValue)),
      s"Json $left was not an array containing $expectedValue",
      s"Json $left was an array containing $expectedValue"
    )
  }
}

object CirceEq {

  val ignoreJsonKeyOrderEq: Eq[Json] = new Eq[Json] {
    implicit private val printer: Printer = Printer.spaces2.copy(dropNullValues = true)

    override def eqv(left: Json, right: Json): Boolean = {
      val leftSorted  = sortKeys(left)
      val rightSorted = sortKeys(right)
      leftSorted == rightSorted || printer.print(leftSorted) == printer.print(rightSorted)
    }
  }

  private def sortKeys(value: Json): Json = {
    def canonicalJson(json: Json): Json =
      json.arrayOrObject[Json](
        json,
        arr => Json.fromValues(arr.map(canonicalJson).sortBy(_.hashCode)),
        obj => sorted(obj).asJson
      )

    def sorted(jObj: JsonObject): JsonObject =
      JsonObject.fromIterable(jObj.toVector.sortBy(_._1).map { case (k, v) => k -> canonicalJson(v) })

    canonicalJson(value)
  }

  final case class IgnoredArrayOrder(json: Json) extends Matcher[Json] {
    implicit private val printer: Printer = Printer.spaces2.copy(dropNullValues = true)

    override def apply(left: Json): MatchResult = {
      val leftSorted  = sortKeys(left)
      val rightSorted = sortKeys(json)
      MatchResult(
        leftSorted == rightSorted || printer.print(leftSorted) == printer.print(rightSorted),
        s"Both Json are not equal (ignoring array order)\n${printer.print(leftSorted)}\ndid not equal\n${printer.print(rightSorted)}",
        ""
      )
    }
  }
}
