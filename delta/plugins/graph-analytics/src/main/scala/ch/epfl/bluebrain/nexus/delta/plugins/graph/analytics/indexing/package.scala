package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonObjectContentOf
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig.TermAggregationsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.JsonObject
import org.typelevel.log4cats

package object indexing {

  implicit private val classLoader: ClassLoader    = getClass.getClassLoader
  implicit private val logger: log4cats.Logger[IO] = Logger[GraphAnalytics]

  val updateRelationshipsScriptId = "updateRelationships"

  val scriptContent: IO[String] =
    ClasspathResourceUtils
      .ioContentOf("elasticsearch/update_relationships_script.painless")
      .onError(e => logger.warn(e)("ElasticSearch script 'update_relationships_script.painless' template not found"))

  val graphAnalyticsMappings: IO[JsonObject] =
    ClasspathResourceUtils
      .ioJsonObjectContentOf("elasticsearch/mappings.json")
      .onError(e => logger.warn(e)("ElasticSearch mapping 'mappings.json' template not found"))

  def propertiesAggQuery(config: TermAggregationsConfig): IO[JsonObject] = ioJsonObjectContentOf(
    "elasticsearch/paths-properties-aggregations.json",
    "shard_size" -> config.shardSize,
    "size"       -> config.size,
    "type"       -> "{{type}}"
  ).logErrors("ElasticSearch 'paths-properties-aggregations.json' template not found")

  def relationshipsAggQuery(config: TermAggregationsConfig): IO[JsonObject] =
    ioJsonObjectContentOf(
      "elasticsearch/paths-relationships-aggregations.json",
      "shard_size" -> config.shardSize,
      "size"       -> config.size
    ).logErrors("ElasticSearch 'paths-relationships-aggregations.json' template not found")

}
