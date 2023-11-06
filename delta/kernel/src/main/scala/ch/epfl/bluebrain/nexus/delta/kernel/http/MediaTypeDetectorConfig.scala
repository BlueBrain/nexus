package ch.epfl.bluebrain.nexus.delta.kernel.http

import akka.http.scaladsl.model.MediaType
import cats.syntax.all._
import pureconfig.ConfigReader
import pureconfig.configurable.genericMapReader
import pureconfig.error.CannotConvert

/**
  * Allows to define custom media types for the given extensions
  */
final case class MediaTypeDetectorConfig(extensions: Map[String, MediaType]) {
  def find(extension: String): Option[MediaType] = extensions.get(extension)

}

object MediaTypeDetectorConfig {

  val Empty = new MediaTypeDetectorConfig(Map.empty)

  def apply(values: (String, MediaType)*) = new MediaTypeDetectorConfig(values.toMap)

  implicit final val mediaTypeDetectorConfigReader: ConfigReader[MediaTypeDetectorConfig] = {
    implicit val mediaTypeConfigReader: ConfigReader[MediaType]  =
      ConfigReader.fromString(str =>
        MediaType
          .parse(str)
          .leftMap(_ => CannotConvert(str, classOf[MediaType].getSimpleName, s"'$str' is not a valid content type."))
      )
    implicit val mapReader: ConfigReader[Map[String, MediaType]] = genericMapReader(Right(_))

    ConfigReader.fromCursor { cursor =>
      for {
        obj           <- cursor.asObjectCursor
        extensionsKey <- obj.atKey("extensions")
        extensions    <- ConfigReader[Map[String, MediaType]].from(extensionsKey)
      } yield MediaTypeDetectorConfig(extensions)
    }
  }

}
