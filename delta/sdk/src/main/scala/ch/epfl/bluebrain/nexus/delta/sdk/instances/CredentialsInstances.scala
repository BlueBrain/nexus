package ch.epfl.bluebrain.nexus.delta.sdk.instances

import akka.http.scaladsl.model.headers.BasicHttpCredentials
import org.http4s.BasicCredentials
import pureconfig.*
import pureconfig.generic.semiauto.deriveReader

trait CredentialsInstances {

  implicit final val basicHttpCredentialsConfigReader: ConfigReader[BasicHttpCredentials] =
    deriveReader[BasicHttpCredentials]

  implicit final val basicCredentialsConfigReader: ConfigReader[BasicCredentials] =
    ConfigReader.forProduct2[BasicCredentials, String, String]("username", "password")(BasicCredentials(_, _))
}

object CredentialsInstances extends CredentialsInstances
