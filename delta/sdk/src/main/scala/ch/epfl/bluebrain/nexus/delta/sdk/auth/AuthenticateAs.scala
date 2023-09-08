package ch.epfl.bluebrain.nexus.delta.sdk.auth

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

case class AuthenticateAs(user: String, password: Secret[String], realm: Label)

object AuthenticateAs {
  @nowarn("cat=unused")
  implicit private val labelConfigReader: ConfigReader[Label] = ConfigReader.fromString(str =>
    Label(str).left.map(e => CannotConvert(str, classOf[Label].getSimpleName, e.getMessage))
  )
  implicit val configReader: ConfigReader[AuthenticateAs]     = deriveReader[AuthenticateAs]
}
