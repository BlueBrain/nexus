package ch.epfl.bluebrain.nexus.delta.sdk.instances

import akka.http.scaladsl.model.headers.BasicHttpCredentials
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

trait CredentialsInstances {

  implicit final val basicHttpCredentialsConfigReader: ConfigReader[BasicHttpCredentials] =
    deriveReader[BasicHttpCredentials]
}

object CredentialsInstances extends CredentialsInstances
