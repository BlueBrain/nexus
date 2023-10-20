package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.toCatsIOOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF

import java.util.UUID

/**
  * Holds some of the metadata information related to the file.
  *
  * @param uuid
  *   the unique id that identifies this file.
  * @param filename
  *   the original filename of the file
  * @param mediaType
  *   the media type of the file
  */
final case class FileDescription(uuid: UUID, filename: String, mediaType: Option[ContentType])

object FileDescription {

  final def apply(filename: String, mediaType: Option[ContentType])(implicit uuidF: UUIDF): IO[FileDescription] =
    uuidF().toCatsIO.map(FileDescription(_, filename, mediaType))

  final def apply(filename: String, mediaType: ContentType)(implicit uuidF: UUIDF): IO[FileDescription] =
    apply(filename, Some(mediaType))
}
