package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * @param forbidMetadataFieldsInPayload
  *   Don't allow _ fields in user-defined payloads
  */
final case class JsonLdSourceProcessorConfig(forbidMetadataFieldsInPayload: Boolean)

object JsonLdSourceProcessorConfig {
  implicit final val jsonLdApiConfigReader: ConfigReader[JsonLdSourceProcessorConfig] =
    deriveReader[JsonLdSourceProcessorConfig]
}
