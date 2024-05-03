package ch.epfl.bluebrain.nexus.ship.config

import akka.http.scaladsl.model.Uri
import cats.syntax.all._
import fs2.aws.s3.models.Models.BucketName
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

import java.net.URI
import scala.util.Try

final case class S3Config(endpoint: URI, importBucket: BucketName, prefix: Option[Uri])

object S3Config {

  implicit final val bucketNameReader: ConfigReader[BucketName] =
    InputConfig.bucketNameReader

  implicit final val uriReader: ConfigReader[Uri] = ConfigReader.fromString(str =>
    Try(Uri(str)).toEither
      .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
  )

  implicit final val s3ConfigReader: ConfigReader[S3Config] =
    deriveReader[S3Config]

}
