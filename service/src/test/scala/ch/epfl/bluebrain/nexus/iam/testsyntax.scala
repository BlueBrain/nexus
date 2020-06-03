package ch.epfl.bluebrain.nexus.iam

import _root_.io.circe._
import ch.epfl.bluebrain.nexus.iam.config.AppConfig

object testsyntax {

  implicit class RichJson(json: Json) {
    private val keys = AppConfig.orderedKeys
    def sort: Json = {
      import _root_.io.circe.syntax._
      implicit val ord: Ordering[String] = new Ordering[String] {
        private val middlePos = keys.withPosition("")

        private def position(key: String): Int = keys.withPosition.getOrElse(key, middlePos)

        override def compare(x: String, y: String): Int = {
          val posX = position(x)
          val posY = position(y)
          if (posX == middlePos && posY == middlePos) x compareTo y
          else posX compareTo posY
        }
      }
      def sortStrings(vector: Vector[Json]): Vector[Json] =
        vector.sortWith { (left, right) =>
          (left.asString, right.asString) match {
            case (Some(lstr), Some(rstr)) => ord.lt(lstr, rstr)
            case _                        => true
          }
        }
      def canonicalJson(json: Json): Json =
        json.arrayOrObject[Json](
          json,
          arr => Json.fromValues(sortStrings(arr.map(canonicalJson))),
          obj => sorted(obj).asJson
        )

      def sorted(jObj: JsonObject): JsonObject =
        JsonObject.fromIterable(jObj.toVector.sortBy(_._1).map { case (k, v) => k -> canonicalJson(v) })

      canonicalJson(json)
    }
  }

}
