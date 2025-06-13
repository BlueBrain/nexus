package ai.senscience.nexus.delta.config

import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.*

/**
  * The http specific configuration.
  * @param interface
  *   the interface to bind to
  * @param port
  *   the port to bind to
  * @param baseUri
  *   the base public uri of the service
  * @param strictEntityTimeout
  *   the timeout to transform the request entity to strict entity
  */
final case class HttpConfig(
    interface: String,
    port: Int,
    baseUri: BaseUri,
    strictEntityTimeout: StrictEntity
)

object HttpConfig {

  implicit final val httpConfigReader: ConfigReader[HttpConfig] =
    deriveReader[HttpConfig]

}
