package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import io.circe.Codec
import io.circe.generic.extras.Configuration
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

case class FileDescription(
    filename: String,
    mediaType: Option[ContentType],
    metadata: Option[FileCustomMetadata]
)

object FileDescription {

  implicit private val config: Configuration                = Configuration.default
  implicit val fileDescriptionCodec: Codec[FileDescription] = deriveConfiguredCodec[FileDescription]

}
