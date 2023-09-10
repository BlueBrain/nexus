package ch.epfl.bluebrain.nexus.delta.sdk.auth

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

sealed trait AuthMethod

object AuthMethod {
  case object Anonymous extends AuthMethod {
    implicit val configReader: ConfigReader[Anonymous.type] = deriveReader[Anonymous.type]
  }

  case class AuthToken(token: String) extends AuthMethod
  case object AuthToken {
    implicit val configReader: ConfigReader[AuthToken] = deriveReader[AuthToken]
  }
  case class Credentials(user: String, password: Secret[String], realm: Label) extends AuthMethod
  object Credentials    {
    @nowarn("cat=unused")
    implicit private val labelConfigReader: ConfigReader[Label] = ConfigReader.fromString(str =>
      Label(str).left.map(e => CannotConvert(str, classOf[Label].getSimpleName, e.getMessage))
    )
    implicit val configReader: ConfigReader[Credentials]        = deriveReader[Credentials]
  }

  implicit val configReader: ConfigReader[AuthMethod] = deriveReader[AuthMethod]
}
