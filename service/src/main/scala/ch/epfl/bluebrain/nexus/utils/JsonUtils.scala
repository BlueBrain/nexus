package ch.epfl.bluebrain.nexus.utils

import ch.epfl.bluebrain.nexus.clients.JsonLdCirceSupport.OrderedKeys
import io.circe.syntax._
import io.circe.{Json, JsonObject}

/**
  * Utilities around Circe Json
  */
trait JsonUtils {

  /**
    * Removes the provided keys from anywhere on the Json nested structure.
    */
  def removeNestedKeys(json: Json, keys: String*): Json = {
    def inner(obj: JsonObject): JsonObject =
      JsonObject.fromIterable(
        obj.filterKeys(!keys.contains(_)).toVector.map { case (k, v) => k -> removeNestedKeys(v, keys: _*) }
      )
    json.arrayOrObject[Json](
      json,
      arr => Json.fromValues(removeEmpty(arr.map(j => removeNestedKeys(j, keys: _*)))),
      obj => inner(obj).asJson
    )
  }

  def removeEmpty(arr: Seq[Json]): Seq[Json] =
    arr.filter(j => j != Json.obj() && j != Json.fromString("") && j != Json.arr())

  /**
    * Removes the provided keys from the top of a Json Object.
    *
    * Note: To remove keys on nested Json structures or Json Arrays use ''removeNestedKeys''.
    */
  def removeKeys(json: Json, keys: String*): Json =
    json.asObject.map(_.filterKeys(!keys.contains(_)).asJson).getOrElse(json)

  /**
    * Order the passed ''json'' using the sequence of keys passed as [[OrderedKeys]].
    */
  def sortKeys(json: Json)(implicit keys: OrderedKeys): Json = {

    implicit val customStringOrdering: Ordering[String] = new Ordering[String] {
      private val middlePos = keys.withPosition("")

      private def position(key: String): Int = keys.withPosition.getOrElse(key, middlePos)

      override def compare(x: String, y: String): Int = {
        val posX = position(x)
        val posY = position(y)
        if (posX == middlePos && posY == middlePos) x compareTo y
        else posX compareTo posY
      }
    }

    def canonicalJson(json: Json): Json =
      json.arrayOrObject[Json](json, arr => Json.fromValues(arr.map(canonicalJson)), obj => sorted(obj).asJson)

    def sorted(jObj: JsonObject): JsonObject =
      JsonObject.fromIterable(jObj.toVector.sortBy(_._1).map { case (k, v) => k -> canonicalJson(v) })

    canonicalJson(json)
  }
}

object JsonUtils extends JsonUtils
