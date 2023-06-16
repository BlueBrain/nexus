package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import io.circe.JsonObject

/**
  * Defines the aggregation result
  * @param total
  *   the total number of results
  * @param value
  *   the value of the aggregations field in the elasticsearch response
  */
final case class AggregationResult(total: Long, value: JsonObject)
