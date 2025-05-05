package ch.epfl.bluebrain.nexus.delta.sdk.instances

import org.http4s.BasicCredentials
import pureconfig.*

trait CredentialsInstances {

  implicit final val basicCredentialsConfigReader: ConfigReader[BasicCredentials] =
    ConfigReader.forProduct2[BasicCredentials, String, String]("username", "password")(BasicCredentials(_, _))
}

object CredentialsInstances extends CredentialsInstances
