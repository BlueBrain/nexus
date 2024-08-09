package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import cats.implicits.catsSyntaxOptionId
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.UploadedFileInformation
import io.circe.Codec
import io.circe.generic.extras.Configuration
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

case class FileDescription(
    filename: String,
    mediaType: Option[ContentType],
    metadata: Option[FileCustomMetadata]
)

object FileDescription {
  def from(file: File): FileDescription = {
    from(file.attributes)
  }

  def from(fileAttributes: FileAttributes): FileDescription =
    FileDescription(
      fileAttributes.filename,
      fileAttributes.mediaType,
      FileCustomMetadata(
        fileAttributes.name,
        fileAttributes.description,
        Some(fileAttributes.keywords)
      ).some
    )

  def from(info: UploadedFileInformation, metadata: Option[FileCustomMetadata]): FileDescription = {
    val md = metadata.getOrElse(FileCustomMetadata.empty)
    FileDescription(
      info.filename,
      Some(info.suppliedContentType),
      FileCustomMetadata(
        md.name,
        md.description,
        md.keywords
      ).some
    )
  }

  implicit private val config: Configuration                = Configuration.default
  implicit val fileDescriptionCodec: Codec[FileDescription] = deriveConfiguredCodec[FileDescription]

}
