package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.JsonLdValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import io.circe.Json

final case class JsonLdContent[A, M](resource: ResourceF[A], source: Json, metadata: Option[M])(implicit
    val encoder: JsonLdEncoder[A]
) {

  def jsonLdValue(implicit base: BaseUri): JsonLdValue = {
    JsonLdValue(resource)
  }
}
