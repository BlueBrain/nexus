package ch.epfl.bluebrain.nexus.delta.rdf.utils

import io.circe.{Encoder, Json, JsonObject}
import io.circe.syntax._

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
    * Removes the provided key value pairs from everywhere on the json.
    */
  def removeAll[A: Encoder](json: Json, keyValues: (String, A)*): Json =
    removeNested(
      json,
      keyValues.map {
        case (k, v) => (kk => kk == k, vv => vv == v.asJson)
      }
    )

  /**
    * Removes the provided values from everywhere on the json.
    */
  def removeAllValues[A: Encoder](json: Json, values: A*): Json =
    removeNested(json, values.map(v => (_ => true, vv => vv == v.asJson)))

  /**
    * Removes the provided keys from everywhere on the json.
    */
  def remoteAllKeys(json: Json, keys: String*): Json =
    removeNested(json, keys.map(k => (kk => kk == k, _ => true)))

  private def removeNested(json: Json, keyValues: Seq[(String => Boolean, Json => Boolean)]): Json = {
    def inner(obj: JsonObject): JsonObject =
      JsonObject.fromIterable(
        obj.filter { case (k, v) => !keyValues.exists { case (fk, fv) => fk(k) && fv(v) } }.toVector.map {
          case (k, v) => k -> removeNested(v, keyValues)
        }
      )
    json.arrayOrObject(
      json,
      arr => Json.fromValues(arr.map(j => removeNested(j, keyValues))),
      obj => Json.fromJsonObject(inner(obj))
    )
  }

  /**
    * Sort all the keys in the passed ''json''.
    *
    * @param json     the json to sort
    * @param ordering the sorting strategy
    */
  def sort(json: Json)(implicit ordering: JsonKeyOrdering): Json = {

    def inner(obj: JsonObject): JsonObject =
      JsonObject.fromIterable(obj.toVector.sortBy(_._1)(ordering).map { case (k, v) => k -> sort(v) })

    json.arrayOrObject[Json](json, arr => Json.fromValues(arr.map(sort)), obj => inner(obj).asJson)
  }
}

object JsonUtils extends JsonUtils
