package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.{JsonKeyOrdering, JsonUtils}
import io.circe.syntax._
import io.circe._

trait JsonSyntax {
  implicit final def jsonOpsSyntax(json: Json): JsonOps                  = new JsonOps(json)
  implicit final def jsonObjectOpsSyntax(obj: JsonObject): JsonObjectOps = new JsonObjectOps(obj)
  implicit final def aCursorOpsSyntax(cursor: ACursor): ACursorOps       = new ACursorOps(cursor)
}

final class ACursorOps(private val cursor: ACursor) extends AnyVal {

  /**
    * Extracts the value of the passed key and attempts to convert it to ''A''.
    *
    * The conversion will first attempt to convert the Json to an A and secondarily it will attempt to convert a Json Array
    * that contains a single entry to an A
    * If the key does not exist, the passed ''defaultValue'' will be returned.
    */
  def getIgnoreSingleArrayOr[A: Decoder](key: String)(defaultValue: => A): Decoder.Result[A] =
    JsonUtils.getIgnoreSingleArrayOr(cursor, key)(defaultValue)

  /**
    * Extracts the value of the passed key and attempts to convert it to ''A''.
    *
    * The conversion will first attempt to convert the Json to an A and secondarily it will attempt to convert a Json Array
    * that contains a single entry to an A
    *
    * @param key the key of the target value
    * @tparam A the target generic type
    */
  def getIgnoreSingleArray[A: Decoder](key: String): Decoder.Result[A] =
    JsonUtils.getIgnoreSingleArray(cursor, key)
}

@SuppressWarnings(Array("OptionGet"))
final class JsonObjectOps(private val obj: JsonObject) extends AnyVal {

  /**
    * @return the value of the top @context key when found, an empty Json otherwise
    */
  def topContextValueOrEmpty: ContextValue = JsonLdContext.topContextValueOrEmpty(obj.asJson)

  /**
    * @return the all the values with key @context
    */
  def contextValues: Set[ContextValue] = JsonLdContext.contextValues(obj.asJson)

  /**
    * Removes the provided keys from the top object on the current json object.
    */
  def removeKeys(keys: String*): JsonObject = JsonUtils.removeKeys(obj.asJson, keys: _*).asObject.get

  /**
    * Removes the provided keys from everywhere on the current json object.
    */
  def removeAllKeys(keys: String*): JsonObject = JsonUtils.removeAllKeys(obj.asJson, keys: _*).asObject.get

  /**
    * Removes the provided key value pairs from everywhere on the json object.
    */
  def removeAll[A: Encoder](keyValues: (String, A)*): JsonObject =
    JsonUtils.removeAll(obj.asJson, keyValues: _*).asObject.get

  /**
    * Removes the provided values from everywhere on the current json object.
    */
  def removeAllValues[A: Encoder](values: A*): JsonObject =
    JsonUtils.removeAllValues(obj.asJson, values: _*).asObject.get

  /**
    * Replace in the current json object with the found key value pairs in ''from'' with the value in ''toValue''
    */
  def replace[A: Encoder, B: Encoder](from: (String, A), toValue: B): JsonObject =
    JsonUtils.replace(obj.asJson, from._1, from._2, toValue).asObject.get

  /**
    * Replace in the current json object with the found value in ''from'' with the value in ''toValue''
    */
  def replace[A: Encoder, B: Encoder](from: A, toValue: B): JsonObject = {
    JsonUtils.replace(obj.asJson, from, toValue).asObject.get
  }

  /**
    * Extract all the values found from the passed ''keys'' in the current json object.
    *
    * @param keys the keys from where to extract the Json values
    */
  def extractValuesFrom(keys: String*): Set[Json] = JsonUtils.extractValuesFrom(obj.asJson, keys: _*)

  /**
    * Sort all the keys in the current json object.
    *
    * @param ordering the sorting strategy
    */
  def sort(implicit ordering: JsonKeyOrdering): JsonObject = JsonUtils.sort(obj.asJson).asObject.get
}

final class JsonOps(private val json: Json) extends AnyVal {

  /**
    * @return the value of the top @context key when found, an empty Json otherwise
    */
  def topContextValueOrEmpty: ContextValue = JsonLdContext.topContextValueOrEmpty(json)

  /**
    * @return the all the values with key @context
    */
  def contextValues: Set[ContextValue] = JsonLdContext.contextValues(json)

  /**
    * Merges the values of the key @context in both existing ''json'' and ''that'' Json documents.
    *
    * @param that the context to append to this json. E.g.: {"@context": {...}}
    * @return a new Json with the original json and the merged context of both passed jsons.
    *         If a key inside the @context is repeated in both jsons, the one in ''that'' will override the one in ''json''
    */
  def addContext(that: Json): Json = JsonLdContext.addContext(json, that)

  /**
    * Adds a context Iri to an existing @context, or creates an @context with the Iri as a value.
    */
  def addContext(iri: Iri): Json = JsonLdContext.addContext(json, iri)

  /**
    * Removes the provided keys from the top object on the current json.
    */
  def removeKeys(keys: String*): Json = JsonUtils.removeKeys(json, keys: _*)

  /**
    * Removes the provided keys from everywhere on the current json.
    */
  def removeAllKeys(keys: String*): Json = JsonUtils.removeAllKeys(json, keys: _*)

  /**
    * Removes the provided key value pairs from everywhere on the json.
    */
  def removeAll[A: Encoder](keyValues: (String, A)*): Json = JsonUtils.removeAll(json, keyValues: _*)

  /**
    * Removes the provided values from everywhere on the current json.
    */
  def removeAllValues[A: Encoder](values: A*): Json = JsonUtils.removeAllValues(json, values: _*)

  /**
    * Replace in the current json the found key value pairs in ''from'' with the value in ''toValue''
    */
  /**
    * Replace in the current json with the found key value pairs in ''from'' with the value in ''toValue''
    */
  def replace[A: Encoder, B: Encoder](from: (String, A), toValue: B): Json =
    JsonUtils.replace(json, from._1, from._2, toValue)

  /**
    * Replace in the current json with the found value in ''from'' with the value in ''toValue''
    */
  def replace[A: Encoder, B: Encoder](from: A, toValue: B): Json =
    JsonUtils.replace(json, from, toValue)

  /**
    * Extract all the values found from the passed ''keys'' in the current json.
    *
    * @param keys the keys from where to extract the Json values
    */
  def extractValuesFrom(keys: String*): Set[Json] = JsonUtils.extractValuesFrom(json, keys: _*)

  /**
    * Sort all the keys in the current json.
    *
    * @param ordering the sorting strategy
    */
  def sort(implicit ordering: JsonKeyOrdering): Json = JsonUtils.sort(json)

  /**
    * Extracts the value of the passed key and attempts to convert it to ''A''.
    *
    * The conversion will first attempt to convert the Json to an A and secondarily it will attempt to convert a Json Array
    * that contains a single entry to an A.
    */
  def getIgnoreSingleArray[A: Decoder](key: String): Decoder.Result[A] = JsonUtils.getIgnoreSingleArray(json, key)

  /**
    * Extracts the value of the passed key and attempts to convert it to ''A''.
    *
    * The conversion will first attempt to convert the Json to an A and secondarily it will attempt to convert a Json Array
    * that contains a single entry to an A.
    * If the key does not exist, the passed ''defaultValue'' will be returned.
    */
  def getIgnoreSingleArrayOr[A: Decoder](key: String)(defaultValue: => A): Decoder.Result[A] =
    JsonUtils.getIgnoreSingleArrayOr(json, key)(defaultValue)
}
