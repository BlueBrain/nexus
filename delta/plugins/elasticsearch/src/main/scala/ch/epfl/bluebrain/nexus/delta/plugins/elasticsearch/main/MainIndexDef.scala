package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.main

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.MainIndexConfig
import io.circe.JsonObject

/**
  * Configuration for the main index
  * @param name
  *   name of the index
  * @param mapping
  *   mapping to apply
  * @param settings
  *   settings to apply
  */
final case class MainIndexDef(name: IndexLabel, mapping: JsonObject, settings: JsonObject)

object MainIndexDef {

  def apply(config: MainIndexConfig, loader: ClasspathResourceLoader): IO[MainIndexDef] =
    for {
      dm <- loader.jsonObjectContentOf("defaults/default-mapping.json")
      ds <- loader.jsonObjectContentOf("defaults/default-settings.json", "number_of_shards" -> config.shards)
    } yield MainIndexDef(config.index, dm, ds)

}
