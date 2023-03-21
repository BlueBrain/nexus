package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.{ioContentOf, ioJsonObjectContentOf}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig.TermAggregationsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import com.typesafe.scalalogging.Logger

package object indexing {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader
  implicit private val logger: Logger           = Logger[GraphAnalytics]

  val updateRelationshipsScriptId = "updateRelationships"

  val scriptContent =
    ioContentOf("elasticsearch/update_relationships_script.painless")
      .logAndDiscardErrors("ElasticSearch script 'update_relationships_script.painless' template not found")

  val graphAnalyticsMappings = ioJsonObjectContentOf("elasticsearch/mappings.json")

  def propertiesAggQuery(config: TermAggregationsConfig) = ioJsonObjectContentOf(
    "elasticsearch/paths-properties-aggregations.json",
    "shard_size" -> config.shardSize,
    "size"       -> config.size,
    "type"       -> "{{type}}"
  ).logAndDiscardErrors("ElasticSearch 'paths-properties-aggregations.json' template not found")

  def relationshipsAggQuery(config: TermAggregationsConfig) =
    ioJsonObjectContentOf(
      "elasticsearch/paths-relationships-aggregations.json",
      "shard_size" -> config.shardSize,
      "size"       -> config.size
    ).logAndDiscardErrors("ElasticSearch 'paths-relationships-aggregations.json' template not found")

}
