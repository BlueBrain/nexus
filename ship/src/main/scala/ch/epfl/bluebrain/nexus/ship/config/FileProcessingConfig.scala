package ch.epfl.bluebrain.nexus.ship.config

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import pureconfig.ConfigReader
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import pureconfig.generic.semiauto.deriveReader

final case class FileProcessingConfig(
    importBucket: String,
    targetBucket: String,
    prefix: Option[Path],
    locationPrefixToStripOpt: Option[Uri],
    skipFileEvents: Boolean,
    mediaTypeDetector: MediaTypeDetectorConfig
)

object FileProcessingConfig {
  implicit final val fileProcessingReader: ConfigReader[FileProcessingConfig] =
    deriveReader[FileProcessingConfig]
      .ensure(_.importBucket.nonEmpty, _ => "importBucket cannot be empty")
      .ensure(_.targetBucket.nonEmpty, _ => "targetBucket cannot be empty")
}
