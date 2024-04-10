package ch.epfl.bluebrain.nexus.ship.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import java.net.URI

final case class S3Config(endpoint: URI, importBucket: String)

object S3Config {

  implicit final val s3ConfigReader: ConfigReader[S3Config] = {
    deriveReader[S3Config]
  }

}
