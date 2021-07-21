package ch.epfl.bluebrain.nexus.delta.plugins.search.models
import ch.epfl.bluebrain.nexus.delta.plugins.search.Search.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.search.models.SearchConfig.FieldsConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import com.typesafe.config.Config
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import monix.bio.UIO
import pureconfig.ConfigSource
import pureconfig.generic.auto._

final case class SearchConfig(fields: FieldsConfig)

object SearchConfig {

  /**
    * Converts a [[Config]] into an [[SearchConfig]]
    */
  def load(config: Config): UIO[SearchConfig] =
    UIO.delay {
      ConfigSource
        .fromConfig(config)
        .at("plugins.search")
        .loadOrThrow[SearchConfig]
    }

  final case class FieldsConfig(value: String) // TODO: To be modified

  object FieldsConfig {
    implicit val fieldsConfigEncoder: Encoder[FieldsConfig] = deriveEncoder

    implicit final val fieldsConfigJsonLdEncoder: JsonLdEncoder[FieldsConfig] =
      JsonLdEncoder.computeFromCirce(ContextValue(contexts.fieldsConfig))
  }
}
