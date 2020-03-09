package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.{Path, Paths}

import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig._
import ch.epfl.bluebrain.nexus.cli.types.{BearerToken, Offset}
import com.typesafe.config.ConfigFactory
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Uri}
import pureconfig.ConfigConvert
import pureconfig.ConvertHelpers.catchReadError
import pureconfig.error.CannotConvert
import pureconfig.generic.auto._
import pureconfig.generic.semiauto.deriveConvert

/**
  * Nexus configuration.
  *
  * @param endpoint   the Nexus service endpoint, including the prefix (if necessary)
  * @param token      the optional Bearer Token used to connect to the Nexus service
  * @param httpClient the HTTP Client configuration
  * @param sse        the SSE configuration
  */
final case class NexusConfig(endpoint: Uri, token: Option[BearerToken], httpClient: ClientConfig, sse: SSEConfig) {

  /**
    * Writes the current config to the passed ''path'' location. If the path file already exists, it overrides its content.
    */
  def write[F[_]](
      path: Path = defaultPath
  )(implicit writer: ConfigWriter[NexusConfig, F]): F[Either[String, Unit]] =
    writer(this, path)

  /**
    * Converts the Bearer Token to the HTTP Header Authorization header
    */
  lazy val authorizationHeader: Option[Authorization] =
    token.map {
      case BearerToken(value) => Authorization(Credentials.Token(AuthScheme.Bearer, value))
    }

}

object NexusConfig {

  private[cli] val defaultPath     = Paths.get(System.getProperty("user.home"), ".nexus.conf")
  private lazy val referenceConfig = ConfigFactory.defaultReference()

  /**
    * Attempts to construct a Nexus configuration from the passed path. If the path is not provided,
    * the default path ~/.nexus.conf will be used.
    *
    * If that path does not exists, the default configuration in ''reference.conf'' will be used.
    */
  def apply(path: Path = defaultPath)(implicit reader: ConfigReader[NexusConfig]): Either[String, NexusConfig] =
    reader(path, referenceConfig)

  /**
    * The HTTP Client configuration
    *
    * @param retry the retry strategy (policy and condition)
    */
  final case class ClientConfig(retry: RetryStrategyConfig)

  /**
    * The SSE configuration
    *
    * @param lastEventId the optionally latest consumed lastEventId (a Sequence or a TimeBased UUID)
    */
  final case class SSEConfig(lastEventId: Option[Offset])

  /**
    * The latest consumed lastEventId (a Sequence or a TimeBased UUID)
    * @param value int value greater than 9
    */
  final case class LastEventIdFreq(value: Int)

  implicit private[config] val uriConfigConvert: ConfigConvert[Uri] =
    ConfigConvert
      .viaNonEmptyString[Uri](s => Uri.fromString(s).leftMap(err => CannotConvert(s, "Uri", err.details)), _.toString)

  implicit private[config] val bearerTokenConfigConvert: ConfigConvert[BearerToken] =
    ConfigConvert.viaNonEmptyString(catchReadError(BearerToken), _.value)

  implicit private[config] val offsetConfigConverter: ConfigConvert[Offset] =
    ConfigConvert.viaNonEmptyString(
      string => Offset(string).toRight(CannotConvert(string, "Offset", "value must be a TimeBased UUID or a Long.")),
      _.asString
    )

  implicit val nexusConfigConvert: ConfigConvert[NexusConfig] =
    deriveConvert[NexusConfig]
}
