package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.UploadedFileInformation
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

case class FileDescription(
    filename: String,
    keywords: Map[Label, String],
    mediaType: Option[ContentType],
    description: Option[String]
)

object FileDescription {
  def from(file: File): FileDescription = {
    from(file.attributes)
  }

  def from(fileAttributes: FileAttributes): FileDescription = {
    FileDescription(
      fileAttributes.filename,
      fileAttributes.keywords,
      fileAttributes.mediaType,
      fileAttributes.description
    )
  }

  def from(info: UploadedFileInformation): FileDescription = {
    FileDescription(info.filename, info.keywords, Some(info.suppliedContentType), info.description)
  }
}
