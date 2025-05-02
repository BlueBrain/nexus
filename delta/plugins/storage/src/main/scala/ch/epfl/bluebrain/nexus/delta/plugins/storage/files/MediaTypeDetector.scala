package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import ch.epfl.bluebrain.nexus.delta.kernel.utils.FileUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.MediaType

/**
  * Allows to detect a media type from incoming files from their extensions when the client has not provided one
  *
  * @param config
  *   the config with a mapping from the extension to the media type
  */
final class MediaTypeDetector(config: MediaTypeDetectorConfig) {

  def apply(filename: String, provided: Option[MediaType], fallback: Option[MediaType]): Option[MediaType] = {
    val extensionOpt = FileUtils.extension(filename)

    def detectFromConfig = for {
      extension       <- extensionOpt
      customMediaType <- config.find(extension)
    } yield customMediaType

    def detectHttp4sFromExtension = extensionOpt.flatMap { e =>
      org.http4s.MediaType.extensionMap.get(e).map(MediaType(_))
    }

    provided
      .orElse(detectFromConfig)
      .orElse(detectHttp4sFromExtension)
      .orElse(fallback)
  }
}
