package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.views

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import io.circe.JsonObject

/**
  * Default configuration for indices
  * @param mapping
  *   mapping to apply
  * @param settings
  *   settings to apply
  */
final case class DefaultIndexDef(mapping: JsonObject, settings: JsonObject)

object DefaultIndexDef {

  def apply(loader: ClasspathResourceLoader): IO[DefaultIndexDef] =
    for {
      dm <- loader.jsonObjectContentOf("defaults/default-mapping.json")
      ds <- loader.jsonObjectContentOf("defaults/default-settings.json", "number_of_shards" -> 1)
    } yield DefaultIndexDef(dm, ds)

}
