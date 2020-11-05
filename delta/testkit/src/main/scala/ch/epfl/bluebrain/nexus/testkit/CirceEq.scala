package ch.epfl.bluebrain.nexus.testkit

import _root_.io.circe._
import _root_.io.circe.syntax._
import ch.epfl.bluebrain.nexus.testkit.CirceEq.IgnoredArrayOrder
import org.scalatest.matchers.{MatchResult, Matcher}

trait CirceEq {
  def equalIgnoreArrayOrder(json: Json): IgnoredArrayOrder = IgnoredArrayOrder(json)
}

object CirceEq {
  final case class IgnoredArrayOrder(json: Json) extends Matcher[Json] {
    implicit private val printer: Printer = Printer.spaces2.copy(dropNullValues = true)
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
