package ch.epfl.bluebrain.nexus.delta.rdf.utils

import ch.epfl.bluebrain.nexus.delta.rdf.utils.IterableUtils.singleEntry
import io.circe._
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
      keyValues.map { case (k, v) =>
        (kk => kk == k, vv => vv == v.asJson)
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
  def removeAllKeys(json: Json, keys: String*): Json =
    removeNested(json, keys.map(k => (kk => kk == k, _ => true)))

  /**
    * Replace in the passed ''json'' the found key ''fromKey'' and value ''fromValue'' with the value in ''toValue''
    */
  def replace[A: Encoder, B: Encoder](json: Json, fromKey: String, fromValue: A, toValue: B): Json = {
    replace(json, (k: String, v: Json) => fromKey == k && fromValue.asJson == v, toValue)
  }

  /**
    * Replace in the passed ''json'' the found key ''fromKey'' with the value in ''toValue''
    */
  def replace[A: Encoder](json: Json, fromKey: String, toValue: A): Json = {
    replace(json, (k: String, _: Json) => fromKey == k, toValue)
  }

  /**
    * Replace in the passed ''json'' the found value ''fromValue'' with the value in ''toValue''
    */
  def replace[A: Encoder, B: Encoder](json: Json, fromValue: A, toValue: B): Json = {
    replace(json, (_: String, v: Json) => fromValue.asJson == v, toValue)
  }

  /**
    * Replace in the passed ''json'' the found key value pairs that matches ''f'' with the value in ''toValue''
    */
  def replace[A: Encoder](json: Json, f: (String, Json) => Boolean, toValue: A): Json = {
    def inner(obj: JsonObject): JsonObject =
      JsonObject.fromIterable(
        obj.toVector.map {
          case (k, v) if f(k, v) => k -> toValue.asJson
          case (k, v)            => k -> replace(v, f, toValue)
        }
      )
    json.arrayOrObject(
      json,
      arr => Json.fromValues(arr.map(j => replace(j, f, toValue))),
      obj => Json.fromJsonObject(inner(obj))
    )
  }

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

  /**
    * Extracts the value of the passed key and attempts to convert it to ''A''.
    *
    * The conversion will first attempt to convert the Json to an A and secondarily it will attempt to convert a Json Array
    * that contains a single entry to an A
    */
  def getIgnoreSingleArray[A: Decoder](json: Json, key: String): Decoder.Result[A] =
    getIgnoreSingleArray(json.hcursor, key)

  /**
    * Extracts the value of the passed key and attempts to convert it to ''A''.
    *
    * The conversion will first attempt to convert the Json to an A and secondarily it will attempt to convert a Json Array
    * that contains a single entry to an A
    * If the key does not exist, the passed ''defaultValue'' will be returned.
    */
  def getIgnoreSingleArrayOr[A: Decoder](json: Json, key: String)(defaultValue: => A): Decoder.Result[A] =
    getIgnoreSingleArrayOr(json.hcursor, key)(defaultValue)

  /**
    * Extracts the value of the passed key and attempts to convert it to ''A''.
    *
    * The conversion will first attempt to convert the Json to an A and secondarily it will attempt to convert a Json Array
    * that contains a single entry to an A
    * If the key does not exist, the passed ''defaultValue'' will be returned.
    */
  def getIgnoreSingleArrayOr[A: Decoder](cursor: ACursor, key: String)(defaultValue: => A): Decoder.Result[A] =
    cursor.getOrElse[A](key)(defaultValue) orElse
      cursor
        .getOrElse[Seq[A]](key)(Seq(defaultValue))
        .flatMap(singleEntry(_).toRight(DecodingFailure("Expected a Json Array with a single entry", cursor.history)))

  /**
    * Extracts the value of the passed key and attempts to convert it to ''A''.
    *
    * The conversion will first attempt to convert the Json to an A and secondarily it will attempt to convert a Json Array
    * that contains a single entry to an A
    */
  def getIgnoreSingleArray[A: Decoder](cursor: ACursor, key: String): Decoder.Result[A] =
    cursor.get[A](key) orElse
      cursor
        .get[Seq[A]](key)
        .flatMap(singleEntry(_).toRight(DecodingFailure("Expected a Json Array with a single entry", cursor.history)))
}

object JsonUtils extends JsonUtils
