package ch.epfl.bluebrain.nexus.cli.config

import cats.implicits._
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
  * @param endpoint the Nexus endpoint, including the prefix (if necessary)
  * @param token    the optional Bearer token value
  * @param retry    the retry strategy
  */
final case class NexusConfig(endpoint: Uri, token: Option[BearerToken], retry: RetryStrategyConfig) {

  /**
    * Converts the Bearer Token to the HTTP Header Authorization header
    */
  lazy val authorizationHeader: Option[Authorization] =
    token.map {
      case BearerToken(value) => Authorization(Credentials.Token(AuthScheme.Bearer, value))
    }

}

object NexusConfig {
  // $COVERAGE-OFF$
  implicit val uriConfigConvert: ConfigConvert[Uri] =
    ConfigConvert
      .viaNonEmptyString[Uri](s => Uri.fromString(s).leftMap(err => CannotConvert(s, "Uri", err.details)), _.toString)

  implicit val bearerTokenConfigConvert: ConfigConvert[BearerToken] =
    ConfigConvert.viaNonEmptyString(catchReadError(BearerToken), _.toString)
  // $COVERAGE-ON$

  implicit val nexusConfigConvert: ConfigConvert[NexusConfig] =
    deriveConvert[NexusConfig]
}
