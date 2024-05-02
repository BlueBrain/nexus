package ch.epfl.bluebrain.nexus.ship.config

import akka.http.scaladsl.model.Uri
import cats.syntax.all._
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

import java.net.URI
import scala.util.Try

final case class S3Config(endpoint: URI, importBucket: String, prefix: Option[Uri])

object S3Config {

  implicit final val uriReader: ConfigReader[Uri] = ConfigReader.fromString(str =>
    Try(Uri(str)).toEither
      .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
  )

  implicit final val s3ConfigReader: ConfigReader[S3Config] =
    deriveReader[S3Config]
      .ensure(config => config.importBucket.nonEmpty, _ => "importBucket cannot be empty")
}
