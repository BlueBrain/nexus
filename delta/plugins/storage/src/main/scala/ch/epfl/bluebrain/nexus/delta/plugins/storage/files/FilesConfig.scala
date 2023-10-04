package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Configuration for the files module.
  *
  * @param eventLog
  *   configuration of the event log
  */
final case class FilesConfig(eventLog: EventLogConfig, mediaTypeDetector: MediaTypeDetectorConfig)

object FilesConfig {
  implicit final val filesConfigReader: ConfigReader[FilesConfig] =
    deriveReader[FilesConfig]
}
