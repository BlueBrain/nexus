package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonUtils
import io.circe.Json
import org.apache.jena.iri.IRI

trait JsonSyntax {
  implicit final def contextSyntax(json: Json): JsonOps = new JsonOps(json)
}

final class JsonOps(private val json: Json) extends AnyVal {

  /**
    * @return the value of the top @context key when found, an empty Json otherwise
    */
  def topContextValueOrEmpty: Json = JsonLdContext.topContextValueOrEmpty(json)

  /**
    * @return the all the values with key @context
    */
  def contextValues: Set[Json] = JsonLdContext.contextValues(json)

  /**
    * Merges the values of the key @context in both existing ''json'' and ''that'' Json documents.
    *
   * @param that the context to append to this json. E.g.: {"@context": {...}}
    * @return a new Json with the original json and the merged context of both passed jsons.
    *         If a key inside the @context is repeated in both jsons, the one in ''that'' will override the one in ''json''
    */
  def addContext(that: Json): Json = JsonLdContext.addContext(json, that)

  /**
    * Adds a context IRI to an existing @context, or creates an @context with the IRI as a value.
    */
  def addContext(iri: IRI): Json = JsonLdContext.addContext(json, iri)

  /**
    * Merge two context value objects.
    * If a key is repeated in both jsons, the one in ''that'' will override the current one.
    *
    * @param that the value of the @context key
    */
  def merge(that: Json): Json = JsonLdContext.merge(json, that)

  /**
    * Removes the provided keys from the top object on the current json.
    */
  def removeKeys(keys: String*): Json = JsonUtils.removeKeys(json, keys: _*)

  /**
    * Removes the provided keys from everywhere on the current json.
    */
  def removeNestedKeys(keys: String*): Json = JsonUtils.removeNestedKeys(json, keys: _*)

  /**
    * Extract all the values found from the passed ''keys'' in the current json.
    *
   * @param keys the keys from where to extract the Json values
    */
  def extractValuesFrom(keys: String*): Set[Json] = JsonUtils.extractValuesFrom(json, keys: _*)
}
