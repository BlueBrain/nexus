package ch.epfl.bluebrain.nexus.commons.circe

import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.rdf.jsonld.JsonLd
import io.circe.Json

trait CirceSyntax {
  implicit final def circeJsonSyntax(json: Json): CirceJsonOps = new CirceJsonOps(json)
}

final class CirceJsonOps(private val json: Json) extends AnyVal {

  def sortKeys(implicit keys: OrderedKeys): Json = JsonKeys.sortKeys(json)

  def addContext(context: ContextUri): Json = JsonLd.addContext(json, context.value)
}
