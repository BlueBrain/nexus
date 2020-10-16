package ch.epfl.bluebrain.nexus.delta.config

import akka.http.scaladsl.model.Uri
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto._

import scala.util.Try

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

  implicit final val baseUriConfigReader: ConfigReader[BaseUri] =
    ConfigReader.fromString(str =>
      Try(Uri(str)).toEither
        .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
        .map(uri => BaseUri(uri))
    )

  implicit final val httpConfigReader: ConfigReader[HttpConfig] =
    deriveReader[HttpConfig]

}
