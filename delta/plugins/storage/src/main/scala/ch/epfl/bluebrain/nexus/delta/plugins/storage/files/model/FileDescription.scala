package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import cats.implicits.catsSyntaxOptionId
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.UploadedFileInformation

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

  def from(info: UploadedFileInformation): FileDescription =
    FileDescription(
      info.filename,
      Some(info.suppliedContentType),
      FileCustomMetadata(
        info.name,
        info.description,
        Some(info.keywords)
      ).some
    )

}
