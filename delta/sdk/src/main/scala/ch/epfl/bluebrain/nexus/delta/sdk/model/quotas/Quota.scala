package ch.epfl.bluebrain.nexus.delta.sdk.model.quotas

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._

/**
  * Quota representation
  *
  * @param resources the maximum number of resources per project
  */
final case class Quota(resources: Int)

object Quota {

  implicit val quotaJsonLdEncoder: JsonLdEncoder[Quota] = {
    implicit val enc: Encoder[Quota] = deriveEncoder[Quota].mapJsonObject(_.add(keywords.tpe, nxv.Quota.asJson))
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.quotas))
  }

}
