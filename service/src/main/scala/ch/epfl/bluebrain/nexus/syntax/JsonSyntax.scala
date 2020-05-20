package ch.epfl.bluebrain.nexus.syntax

import ch.epfl.bluebrain.nexus.clients.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.utils.JsonUtils
import io.circe.Json

trait JsonSyntax {
  implicit def jsonUtilsSyntax(json: Json) = new JsonUtilsOps(json)
}

final class JsonUtilsOps(private val json: Json) extends AnyVal {
  def removeNestedKeys(keys: String*): Json =
    JsonUtils.removeNestedKeys(json, keys: _*)

  def removeKeys(keys: String*): Json =
    JsonUtils.removeKeys(json, keys: _*)

  def sortKeys(implicit keys: OrderedKeys): Json =
    JsonUtils.sortKeys(json)
}
