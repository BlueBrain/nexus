package ch.epfl.bluebrain.nexus.delta.sdk.auth

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

case class AuthenticateAs(user: String, password: Secret[String], realm: String)

object AuthenticateAs {
  implicit val configReader: ConfigReader[AuthenticateAs] = deriveReader[AuthenticateAs]
}
