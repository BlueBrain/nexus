package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.ProgressStatistics
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Statistics for a single composite view projection
  *
  * @param sourceId
  *   the Iri of the composite view source
  * @param projectionId
  *   the Iri of the composite view projection
  * @param value
  *   the statistics value
  */
final case class ProjectionStatistics(sourceId: Iri, projectionId: Iri, value: ProgressStatistics)

object ProjectionStatistics {

  implicit val projectionStatisticsOrdering: Ordering[ProjectionStatistics] =
    Ordering.by[ProjectionStatistics, String](_.sourceId.toString).orElseBy(_.projectionId.toString)

  implicit val projectionStatisticsEncoder: Encoder.AsObject[ProjectionStatistics] =
    Encoder.encodeJsonObject.contramapObject { case ProjectionStatistics(source, projection, stats) =>
      JsonObject("sourceId" -> source.asJson, "projectionId" -> projection.asJson) deepMerge stats.asJsonObject
    }
}
