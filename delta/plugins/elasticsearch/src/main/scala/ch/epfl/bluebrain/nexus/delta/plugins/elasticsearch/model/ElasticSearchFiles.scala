package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.FilesCache
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}

trait ElasticSearchFiles {

  /**
    * Default elasticsearch mapping for a view
    */
  def defaultElasticsearchMapping: IO[JsonObject]

  /**
    * Default elasticsearch settings for a view
    */
  def defaultElasticsearchSettings: IO[JsonObject]

  def emptyResults: IO[Json]

  /** Mapping for the event metrics index */
  def metricsMapping: IO[JsonObject]

  /** Settings for the event metrics index */
  def metricsSettings: IO[JsonObject]
}

object ElasticSearchFiles {

  def mk(filesCache: FilesCache): ElasticSearchFiles = new ElasticSearchFiles {
    override def defaultElasticsearchMapping: IO[JsonObject] =
      filesCache.lookupLogErrors("defaults/default-mapping.json", "loading default elasticsearch mapping")

    override def defaultElasticsearchSettings: IO[JsonObject] =
      filesCache.lookupLogErrors("defaults/default-settings.json", "loading default elasticsearch settings")

    override def emptyResults: IO[Json] =
      filesCache.lookupLogErrors("defaults/empty-results.json", "loading empty elasticsearch results").map(_.asJson)

    override def metricsMapping: IO[JsonObject] =
      filesCache.lookupLogErrors("metrics/metrics-mapping.json", "loading metrics mapping")

    override def metricsSettings: IO[JsonObject] =
      filesCache.lookupLogErrors("metrics/metrics-settings.json", "loading metrics settings")
  }
}
