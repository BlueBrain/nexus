package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.http.scaladsl.model.{ContentType, HttpCharsets, MediaType, MediaTypes}
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.FileUtils

import scala.util.Try

/**
  * Allows to detect a content type from incoming files from their extensions when the client has not provided one
  *
  * @param config
  *   the config with a mapping from the extension to the content type
  */
final class MediaTypeDetector(config: MediaTypeDetectorConfig) {

  def apply(filename: String, provided: Option[ContentType], fallback: Option[ContentType]): Option[ContentType] = {
    val extensionOpt = FileUtils.extension(filename)

    def detectFromConfig = for {
      extension       <- extensionOpt
      customMediaType <- config.find(extension)
    } yield contentType(customMediaType)

    def detectAkkaFromExtension = extensionOpt.flatMap { e =>
      Try(MediaTypes.forExtension(e)).map(contentType).toOption
    }

    provided
      .orElse(detectFromConfig)
      .orElse(detectAkkaFromExtension)
      .orElse(fallback)
  }

  private def contentType(mediaType: MediaType) = ContentType(mediaType, () => HttpCharsets.`UTF-8`)

}
