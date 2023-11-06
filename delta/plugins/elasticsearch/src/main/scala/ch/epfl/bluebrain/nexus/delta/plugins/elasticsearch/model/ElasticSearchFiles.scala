package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.CatsEffectsClasspathResourceUtils
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}

trait ElasticSearchFiles {

  /**
    * Default elasticsearch mapping for a view
    */
  def defaultElasticsearchMapping: DefaultMapping

  /**
    * Default elasticsearch settings for a view
    */
  def defaultElasticsearchSettings: DefaultSettings

  def emptyResults: EmptyResults

  /** Mapping for the event metrics index */
  def metricsMapping: MetricsMapping

  /** Settings for the event metrics index */
  def metricsSettings: MetricsSettings
}

final case class DefaultMapping(value: JsonObject)
final case class DefaultSettings(value: JsonObject)
final case class EmptyResults(value: Json)
final case class MetricsMapping(value: JsonObject)
final case class MetricsSettings(value: JsonObject)

object ElasticSearchFiles {

  implicit private val cl: ClassLoader = getClass.getClassLoader

  private def fetchFile(resourcePath: String) = CatsEffectsClasspathResourceUtils.ioJsonObjectContentOf(resourcePath)

  def apply(): IO[ElasticSearchFiles] =
    for {
      defaultMapping <- fetchFile("defaults/default-mapping.json")
      defaultSettings <- fetchFile("defaults/default-settings.json")
      empty <- fetchFile("defaults/empty-results.json").map(_.asJson)
      mm <- fetchFile("metrics/metrics-mapping.json")
      ms <- fetchFile("metrics/metrics-settings.json")
    } yield new ElasticSearchFiles {
      val defaultElasticsearchMapping: DefaultMapping = DefaultMapping(defaultMapping)
      val defaultElasticsearchSettings: DefaultSettings = DefaultSettings(defaultSettings)
      val emptyResults: EmptyResults = EmptyResults(empty)
      val metricsMapping: MetricsMapping = MetricsMapping(mm)
      val metricsSettings: MetricsSettings = MetricsSettings(ms)
    }
}
