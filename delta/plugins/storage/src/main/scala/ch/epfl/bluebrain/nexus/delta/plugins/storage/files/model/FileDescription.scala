package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

case class FileDescription(
    filename: String,
    mediaType: Option[MediaType],
    metadata: Option[FileCustomMetadata]
)

object FileDescription {

  implicit private val config: Configuration                = Configuration.default
  implicit val fileDescriptionCodec: Codec[FileDescription] = deriveConfiguredCodec[FileDescription]

}
