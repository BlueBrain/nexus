package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.ContentTypes.`application/octet-stream`
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import monix.bio.UIO

import java.util.UUID

/**
  * Holds some of the metadata information related to the file.
  *
  * @param uuid      the unique id that identifies this file.
  * @param filename  the original filename of the file
  * @param mediaType the media type of the file
  */
final case class FileDescription(uuid: UUID, filename: String, mediaType: Option[ContentType]) {
  val defaultMediaType: ContentType = mediaType.getOrElse(`application/octet-stream`)
}

object FileDescription {

  final def apply(filename: String, mediaType: Option[ContentType])(implicit uuidF: UUIDF): UIO[FileDescription] =
    uuidF().map(FileDescription(_, filename, mediaType))

  final def apply(filename: String, mediaType: ContentType)(implicit uuidF: UUIDF): UIO[FileDescription] =
    apply(filename, Some(mediaType))
}
