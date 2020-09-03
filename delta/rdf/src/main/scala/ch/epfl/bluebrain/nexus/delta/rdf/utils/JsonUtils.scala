package ch.epfl.bluebrain.nexus.delta.rdf.utils

import io.circe.{Json, JsonObject}

trait JsonUtils {

  /**
    * Removes the provided keys from the top object on the json.
    */
  def removeKeys(json: Json, keys: String*): Json = {
    def inner(obj: JsonObject): JsonObject = obj.filterKeys(!keys.contains(_))
    json.arrayOrObject(
      json,
      arr => Json.fromValues(arr.map(j => removeKeys(j, keys: _*))),
      obj => Json.fromJsonObject(inner(obj))
    )
  }

  /**
    * Extract all the values found from the passed ''keys''
    *
   * @param json the target json
    * @param keys the keys from where to extract the Json values
    */
  def extractValuesFrom(json: Json, keys: String*): Set[Json] = {

    def inner(obj: JsonObject): Iterable[Json] =
      obj.toVector.flatMap {
        case (k, v) if keys.contains(k) => Vector(v)
        case (_, v)                     => extractValuesFrom(v, keys: _*)
      }

    json
      .arrayOrObject(
        Vector.empty[Json],
        arr => arr.flatMap(j => extractValuesFrom(j, keys: _*)),
        obj => inner(obj).toVector
      )
      .toSet
  }

  /**
    * Removes the provided keys from everywhere on the json.
    */
  def removeNestedKeys(json: Json, keys: String*): Json = {
    def inner(obj: JsonObject): JsonObject =
      JsonObject.fromIterable(
        obj.filterKeys(!keys.contains(_)).toVector.map { case (k, v) => k -> removeNestedKeys(v, keys: _*) }
      )
    json.arrayOrObject(
      json,
      arr => Json.fromValues(arr.map(j => removeNestedKeys(j, keys: _*))),
      obj => Json.fromJsonObject(inner(obj))
    )
  }
}

object JsonUtils extends JsonUtils
