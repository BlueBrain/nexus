package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/**
  * Custom metadata for a file that can be specified by the user.
  */
case class FileCustomMetadata(
    name: Option[String],
    description: Option[String],
    keywords: Option[Map[Label, String]]
)

object FileCustomMetadata {

  implicit val fileUploadMetadataDecoder: Codec[FileCustomMetadata] =
    deriveCodec[FileCustomMetadata]

  val empty: FileCustomMetadata = FileCustomMetadata(None, None, None)

}
