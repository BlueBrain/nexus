package ch.epfl.bluebrain.nexus.delta.sdk.auth

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

/**
  * Enumerates the different ways to obtain an auth toke for making requests to a remote service
  */
sealed trait Credentials

object Credentials {

  /**
    * When no auth token should be used
    */
  case object Anonymous extends Credentials {
    implicit val configReader: ConfigReader[Anonymous.type] = deriveReader[Anonymous.type]
  }

  /**
    * When a long-lived auth token should be used (legacy, not recommended)
    */
  case class JWTToken(token: String) extends Credentials
  case object JWTToken {
    implicit val configReader: ConfigReader[JWTToken] = deriveReader[JWTToken]
  }

  /**
    * When client credentials should be exchanged with an OpenId service to obtain an auth token
    * @param realm
    *   the realm which defines the OpenId service
    */
  case class ClientCredentials(user: String, password: Secret[String], realm: Label) extends Credentials
  object ClientCredentials {
    @nowarn("cat=unused")
    implicit private val labelConfigReader: ConfigReader[Label] = ConfigReader.fromString(str =>
      Label(str).left.map(e => CannotConvert(str, classOf[Label].getSimpleName, e.getMessage))
    )
    implicit val configReader: ConfigReader[ClientCredentials]  = deriveReader[ClientCredentials]
  }

  implicit val configReader: ConfigReader[Credentials] = deriveReader[Credentials]
}
