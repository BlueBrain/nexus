package ch.epfl.bluebrain.nexus.cli.config

import java.nio.file.{Path, Paths}

import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig._
import ch.epfl.bluebrain.nexus.cli.types.BearerToken
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
  */
final case class NexusConfig(endpoint: Uri, token: Option[BearerToken], httpClient: ClientConfig) {

  /**
    * Writes the current config to the passed ''path'' location. If the path file already exists, it overrides its content.
    */
  def write[F[_]](
      path: Path = defaultPath
  )(implicit writer: ConfigWriter[NexusConfig, F]): F[Either[String, Unit]] =
    writer(this, path, prefix)

  /**
    * Converts the Bearer Token to the HTTP Header Authorization header
    */
  lazy val authorizationHeader: Option[Authorization] =
    token.map {
      case BearerToken(value) => Authorization(Credentials.Token(AuthScheme.Bearer, value))
    }

}

object NexusConfig {

  private[cli] val defaultPath     = Paths.get(System.getProperty("user.home"), ".nexus", "app.conf")
  private[cli] val prefix          = "app"
  private lazy val referenceConfig = ConfigFactory.defaultReference()

  /**
    * Attempts to construct a Nexus configuration from the passed path. If the path is not provided,
    * the default path ~/.nexus/app.conf will be used.
    *
    * If that path does not exists, the default configuration in ''reference.conf'' will be used.
    */
  def apply(path: Path = defaultPath)(implicit reader: ConfigReader[NexusConfig]): Either[String, NexusConfig] =
    reader(path, prefix, referenceConfig)

  /**
    * Attempts to construct a Nexus configuration from the passed path. If the path is not provided,
    * the default path ~/.nexus/app.conf will be used.
    * If that path does not exists, the default configuration in ''reference.conf'' will be used.
    *
    * The rest of the parameters, if present, will override the resulting Nexus configuration parameters.
    */
  def withDefaults(
      path: Path = defaultPath,
      endpoint: Option[Uri] = None,
      token: Option[BearerToken] = None,
      httpClient: Option[ClientConfig] = None
  ): Either[String, NexusConfig] =
    apply(path).map { config =>
      config.copy(
        endpoint = mergeOpt(config.endpoint, endpoint),
        token = mergeOpt(config.token, token),
        httpClient = mergeOpt(config.httpClient, httpClient)
      )
    }

  private def mergeOpt[A](one: Option[A], other: Option[A]): Option[A] = other orElse one

  private def mergeOpt[A](one: A, other: Option[A]): A = other.getOrElse(one)

  /**
    * The HTTP Client configuration
    *
    * @param retry the retry strategy (policy and condition)
    */
  final case class ClientConfig(retry: RetryStrategyConfig)

  implicit private[config] val uriConfigConvert: ConfigConvert[Uri] =
    ConfigConvert
      .viaNonEmptyString[Uri](s => Uri.fromString(s).leftMap(err => CannotConvert(s, "Uri", err.details)), _.toString)

  implicit private[config] val bearerTokenConfigConvert: ConfigConvert[BearerToken] =
    ConfigConvert.viaNonEmptyString(catchReadError(BearerToken), _.value)

  implicit val nexusConfigConvert: ConfigConvert[NexusConfig] =
    deriveConvert[NexusConfig]
}
