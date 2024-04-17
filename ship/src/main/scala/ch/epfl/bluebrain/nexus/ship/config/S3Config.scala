package ch.epfl.bluebrain.nexus.ship.config

import fs2.aws.s3.models.Models.BucketName
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import java.net.URI

final case class S3Config(endpoint: URI, importBucket: BucketName)

object S3Config {

  implicit final val bucketNameReader: ConfigReader[BucketName] =
    InputConfig.bucketNameReader

  implicit final val s3ConfigReader: ConfigReader[S3Config] =
    deriveReader[S3Config]

}
