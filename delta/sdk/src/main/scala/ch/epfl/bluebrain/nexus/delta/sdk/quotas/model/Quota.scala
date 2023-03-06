package ch.epfl.bluebrain.nexus.delta.sdk.quotas.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.QuotasConfig.QuotaConfig
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax._

/**
  * Quota representation
  *
  * @param resources
  *   the maximum number of resources per project
  * @param events
  *   the maximum number of events per project
  */
final case class Quota(resources: Option[Int], events: Option[Int])

object Quota {

  final def apply(config: QuotaConfig): Quota =
    Quota(config.resources, config.events)

  implicit val quotaJsonLdEncoder: JsonLdEncoder[Quota] = {
    implicit val enc: Encoder[Quota] = deriveEncoder[Quota].mapJsonObject(_.add(keywords.tpe, nxv.Quota.asJson))
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.quotas))
  }

}
