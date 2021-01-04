package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import pureconfig.ConfigReader
import pureconfig.generic.semiauto._

/**
  * The http specific configuration.
  * @param interface the interface to bind to
  * @param port      the port to bind to
  * @param baseUri   the base public uri of the service
  */
final case class HttpConfig(
    interface: String,
    port: Int,
    baseUri: BaseUri
)

object HttpConfig {

  implicit final val httpConfigReader: ConfigReader[HttpConfig] =
    deriveReader[HttpConfig]

}
