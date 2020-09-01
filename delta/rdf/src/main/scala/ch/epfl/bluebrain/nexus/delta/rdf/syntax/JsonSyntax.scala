package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonUtils
import io.circe.Json
import org.apache.jena.iri.IRI

trait JsonSyntax {
  implicit final def contextSyntax(json: Json): JsonOps = new JsonOps(json)
}

final class JsonOps(private val json: Json) extends AnyVal {

  def topContextValue: Option[Json] = JsonLdContext.topContextValue(json)

  def contextValues: Set[Json] = JsonLdContext.contextValues(json)

  def topContextValueOrEmpty: Json = JsonLdContext.topContextValueOr(json, Json.obj())

  def addContext(iri: IRI): Json = JsonLdContext.addContext(json, iri)

  def addContext(that: Json): Json = JsonLdContext.addContext(json, that)

  def removeKeys(keys: String*): Json = JsonUtils.removeKeys(json, keys: _*)

  def removeNestedKeys(keys: String*): Json = JsonUtils.removeNestedKeys(json, keys: _*)

  def extractValuesFrom(keys: String*): Set[Json] = JsonUtils.extractValuesFrom(json, keys: _*)
}
