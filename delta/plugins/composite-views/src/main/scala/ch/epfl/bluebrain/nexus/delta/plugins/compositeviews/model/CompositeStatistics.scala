package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.ProgressStatistics
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Statistics for a single composite view projection
  *
  * @param sourceId     the Iri of the composite view source
  * @param projectionId the Iri of the composite view projection
  * @param value        the statistics value
  */
final case class CompositeStatistics(sourceId: Iri, projectionId: Iri, value: ProgressStatistics)

object CompositeStatistics {

  implicit val compositeStatisticsSort: Ordering[CompositeStatistics] =
    Ordering.by[CompositeStatistics, String](_.sourceId.toString).orElseBy(_.projectionId.toString)

  implicit private[model] val compositeStatsEncoder: Encoder.AsObject[CompositeStatistics] =
    Encoder.encodeJsonObject.contramapObject { case CompositeStatistics(source, projection, stats) =>
      JsonObject("sourceId" -> source.asJson, "projectionId" -> projection.asJson) deepMerge stats.asJsonObject
    }

  implicit val compositeStatsJsonLdEncoder: JsonLdEncoder[CompositeStatistics] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.statistics))
}
