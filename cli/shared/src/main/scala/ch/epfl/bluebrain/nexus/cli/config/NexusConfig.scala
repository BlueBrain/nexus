package ch.epfl.bluebrain.nexus.cli.config

import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig.ClientConfig
import ch.epfl.bluebrain.nexus.cli.types.BearerToken
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Uri}
import pureconfig.ConfigConvert
import pureconfig.ConvertHelpers.catchReadError
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * Nexus configuration.
  *
  * @param endpoint   the Nexus service endpoint, including the prefix (if necessary)
  * @param token      the optional Bearer Token used to connect to the Nexus service
  * @param httpClient the HTTP Client configuration
  */
final case class NexusConfig(endpoint: Uri, token: Option[BearerToken], httpClient: ClientConfig) {

  /**
    * Converts the Bearer Token to the HTTP Header Authorization header
    */
  lazy val authorizationHeader: Option[Authorization] =
    token.map {
      case BearerToken(value) => Authorization(Credentials.Token(AuthScheme.Bearer, value))
    }

}

object NexusConfig {

  /**
    * The HTTP Client configuration
    *
    * @param retry the retry strategy (policy and condition)
    */
  final case class ClientConfig(retry: RetryStrategyConfig)

  // $COVERAGE-OFF$
  implicit val uriConfigConvert: ConfigConvert[Uri] =
    ConfigConvert
      .viaNonEmptyString[Uri](s => Uri.fromString(s).leftMap(err => CannotConvert(s, "Uri", err.details)), _.toString)

  implicit val bearerTokenConfigConvert: ConfigConvert[BearerToken] =
    ConfigConvert.viaNonEmptyString(catchReadError(BearerToken), _.toString)
  // $COVERAGE-ON$

  implicit val httpClientConfigConvert: ConfigConvert[ClientConfig] =
    deriveConvert[ClientConfig]

  implicit val nexusConfigConvert: ConfigConvert[NexusConfig] =
    deriveConvert[NexusConfig]
}
