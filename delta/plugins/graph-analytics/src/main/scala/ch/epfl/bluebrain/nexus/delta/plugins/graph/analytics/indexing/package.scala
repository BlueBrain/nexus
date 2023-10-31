package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.CatsEffectsClasspathResourceUtils.ioJsonObjectContentOf
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig.TermAggregationsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import com.typesafe.scalalogging.{Logger => ScalaLog}
import io.circe.JsonObject
import monix.bio.UIO
import org.typelevel.log4cats.{Logger => Log4CatsLogger}

package object indexing {

  implicit private val classLoader: ClassLoader     = getClass.getClassLoader
  implicit private val scalaLogger: ScalaLog        = ScalaLog[GraphAnalytics]
  implicit private val log4cats: Log4CatsLogger[IO] = Logger.cats[GraphAnalytics]

  val updateRelationshipsScriptId = "updateRelationships"

  val scriptContent: UIO[String] =
    ClasspathResourceUtils
      .ioContentOf("elasticsearch/update_relationships_script.painless")
      .logAndDiscardErrors("ElasticSearch script 'update_relationships_script.painless' template not found")

  val graphAnalyticsMappings: UIO[JsonObject] =
    ClasspathResourceUtils
      .ioJsonObjectContentOf("elasticsearch/mappings.json")
      .logAndDiscardErrors("ElasticSearch mapping 'mappings.json' template not found")

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
