package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import io.circe.JsonObject

/**
  * Configuration for the index for event metrics
  * @param name
  *   name of the index
  * @param mapping
  *   mapping to apply
  * @param settings
  *   settings to apply
  */
final case class MetricsIndexDef(name: IndexLabel, mapping: JsonObject, settings: JsonObject)

object MetricsIndexDef {

  def apply(prefix: String, loader: ClasspathResourceLoader): IO[MetricsIndexDef] =
    for {
      mm <- loader.jsonObjectContentOf("metrics/metrics-mapping.json")
      ms <- loader.jsonObjectContentOf("metrics/metrics-settings.json")
    } yield MetricsIndexDef(IndexLabel.unsafe(s"${prefix}_project_metrics"), mm, ms)

}
