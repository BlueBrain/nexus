package ch.epfl.bluebrain.nexus.ship.config

import akka.http.scaladsl.model.Uri.Path
import pureconfig.ConfigReader
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import pureconfig.generic.semiauto.deriveReader

final case class FileProcessingConfig(
    importBucket: String,
    targetBucket: String,
    prefix: Option[Path],
    skipFileEvents: Boolean
)

object FileProcessingConfig {
  implicit final val fileProcessingReader: ConfigReader[FileProcessingConfig] =
    deriveReader[FileProcessingConfig]
      .ensure(_.importBucket.nonEmpty, _ => "importBucket cannot be empty")
      .ensure(_.targetBucket.nonEmpty, _ => "targetBucket cannot be empty")
}
