package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}

/**
  * @param defaultMapping
  *   Default elasticsearch mapping for a view
  * @param defaultSettings
  *   Default elasticsearch settings for a view
  * @param emptyResults
  *   Empty results JSON for an elasticsearch query
  * @param metricsMapping
  *   Mapping for the event metrics index
  * @param metricsSettings
  *   Settings for the event metrics index
  */
final case class ElasticSearchFiles(
    defaultMapping: DefaultMapping,
    defaultSettings: DefaultSettings,
    emptyResults: EmptyResults,
    metricsMapping: MetricsMapping,
    metricsSettings: MetricsSettings
)

final case class DefaultMapping(value: JsonObject)
final case class DefaultSettings(value: JsonObject)
final case class EmptyResults(value: Json)
final case class MetricsMapping(value: JsonObject)
final case class MetricsSettings(value: JsonObject)

object ElasticSearchFiles {

  def mk(loader: ClasspathResourceLoader): IO[ElasticSearchFiles] = {
    for {
      dm    <- loader.jsonObjectContentOf("defaults/default-mapping.json")
      ds    <- loader.jsonObjectContentOf("defaults/default-settings.json")
      empty <- loader.jsonObjectContentOf("defaults/empty-results.json").map(_.asJson)
      mm    <- loader.jsonObjectContentOf("metrics/metrics-mapping.json")
      ms    <- loader.jsonObjectContentOf("metrics/metrics-settings.json")
    } yield new ElasticSearchFiles(
      DefaultMapping(dm),
      DefaultSettings(ds),
      EmptyResults(empty),
      MetricsMapping(mm),
      MetricsSettings(ms)
    )
  }
}
