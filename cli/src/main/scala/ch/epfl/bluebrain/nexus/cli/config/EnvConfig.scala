package ch.epfl.bluebrain.nexus.cli.config

import ch.epfl.bluebrain.nexus.cli.sse.{BearerToken, OrgUuid, ProjectUuid}
import ch.epfl.bluebrain.nexus.cli.utils.Codecs
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials, Uri}
import pureconfig.ConfigConvert
import pureconfig.generic.semiauto.deriveConvert

/**
  * Environment configuration.
  *
  * @param endpoint          the Nexus service endpoint, including the prefix (if necessary)
  * @param token             the optional Bearer Token used to connect to the Nexus service
  * @param httpClient        the HTTP Client configuration
  * @param defaultSparqlView the default project sparql view
  */
final case class EnvConfig(
    endpoint: Uri,
    token: Option[BearerToken],
    httpClient: ClientConfig,
    defaultSparqlView: Uri
) {

  /**
    * Converts the Bearer Token to the HTTP Header Authorization header
    */
  lazy val authorizationHeader: Option[Authorization] =
    token.map {
      case BearerToken(value) => Authorization(Credentials.Token(AuthScheme.Bearer, value))
    }

  /**
    * Computes the project endpoint from the arguments.
    *
    * @param org  the organization uuid
    * @param proj the project uuid
    */
  def project(org: OrgUuid, proj: ProjectUuid): Uri =
    endpoint / "projects" / org.value.toString / proj.value.toString
}

object EnvConfig extends Codecs {
  implicit final val envConfigConvert: ConfigConvert[EnvConfig] =
    deriveConvert[EnvConfig]
}
