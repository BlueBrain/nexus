package ch.epfl.bluebrain.nexus.ship.config

import cats.implicits.toBifunctorOps
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import fs2.aws.s3.models.Models.BucketName
import pureconfig.ConfigReader
import pureconfig.error.FailureReason
import pureconfig.generic.semiauto.deriveReader

import java.net.URI
import scala.annotation.nowarn

final case class S3Config(endpoint: URI, importBucket: BucketName, storages: StoragesConfig, defaultBucket: BucketName)

object S3Config {

  @nowarn("cat=unused")
  implicit final val s3ConfigReader: ConfigReader[S3Config] = {
    val emptyBucketName                                     = new FailureReason {
      override def description: String = "The s3 bucket name cannot be empty"
    }
    implicit val bucketNameReader: ConfigReader[BucketName] =
      ConfigReader[String]
        .emap(str => refineV[NonEmpty](str).leftMap(_ => emptyBucketName).map(BucketName.apply))
    deriveReader[S3Config]
  }

}
