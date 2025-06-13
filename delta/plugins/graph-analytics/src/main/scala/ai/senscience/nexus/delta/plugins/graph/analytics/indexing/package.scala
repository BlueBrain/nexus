package ai.senscience.nexus.delta.plugins.graph.analytics

import ai.senscience.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig.TermAggregationsConfig
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import io.circe.JsonObject
import org.typelevel.log4cats

package object indexing {

  implicit private val logger: log4cats.Logger[IO] = Logger[GraphAnalytics]
  private val loader                               = ClasspathResourceLoader.withContext(classOf[GraphAnalyticsPluginModule])

  val updateRelationshipsScriptId = "updateRelationships"

  val scriptContent: IO[String] =
    loader
      .contentOf("elasticsearch/update_relationships_script.painless")
      .onError { case e =>
        logger.warn(e)("ElasticSearch script 'update_relationships_script.painless' template not found")
      }

  val graphAnalyticsMappings: IO[JsonObject] =
    loader
      .jsonObjectContentOf("elasticsearch/mappings.json")
      .onError { case e => logger.warn(e)("ElasticSearch mapping 'mappings.json' template not found") }

  def propertiesAggQuery(config: TermAggregationsConfig): IO[JsonObject] = loader
    .jsonObjectContentOf(
      "elasticsearch/paths-properties-aggregations.json",
      "shard_size" -> config.shardSize,
      "size"       -> config.size,
      "type"       -> "{{type}}"
    )
    .logErrors("ElasticSearch 'paths-properties-aggregations.json' template not found")

  def relationshipsAggQuery(config: TermAggregationsConfig): IO[JsonObject] =
    loader
      .jsonObjectContentOf(
        "elasticsearch/paths-relationships-aggregations.json",
        "shard_size" -> config.shardSize,
        "size"       -> config.size
      )
      .logErrors("ElasticSearch 'paths-relationships-aggregations.json' template not found")

}
