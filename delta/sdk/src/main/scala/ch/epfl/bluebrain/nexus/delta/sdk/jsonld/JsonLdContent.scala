package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.JsonLdValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tags
import io.circe.Json

/**
  * Describes a resource in a common way for transversal operations (indexing, resource resolution)
  * @param resource
  *   the resource as a [[ResourceF]]
  * @param source
  *   the resource original payload
  * @param tags
  *   the resource tags
  * @param encoder
  *   its JSON-LD encoder
  * @tparam A
  *   the resource type
  */
final case class JsonLdContent[A](resource: ResourceF[A], source: Json, tags: Tags)(implicit
    val encoder: JsonLdEncoder[A]
) {

  def jsonLdValue(implicit base: BaseUri): JsonLdValue = {
    JsonLdValue(resource)
  }
}
