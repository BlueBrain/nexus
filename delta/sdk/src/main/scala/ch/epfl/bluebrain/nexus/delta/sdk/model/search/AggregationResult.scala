package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import io.circe.syntax.KeyOps
import io.circe.{Encoder, Json, JsonObject}

/**
  * Defines the aggregation result
  * @param total
  *   the total number of docs that have been aggregated
  * @param value
  *   the value of the aggregations field in the elasticsearch response
  */
final case class AggregationResult(total: Long, value: JsonObject)

object AggregationResult {

  // vocabulary used for root fields of aggregation results
  private val total        = "total"
  private val aggregations = "aggregations"

  implicit val aggregationResultEncoder: Encoder[AggregationResult] =
    Encoder.instance[AggregationResult] { agg =>
      Json.obj(
        total        := agg.total,
        aggregations := agg.value
      )
    }

}
