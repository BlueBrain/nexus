package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.instances._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

case class FileExtraFields(
    storage: Iri,
    storageType: StorageType,
    physicalFile: Option[Int],
    bytes: Option[Long],
    mediaType: Option[ContentType],
    origin: Option[FileAttributesOrigin]
)

object FileExtraFields {
  def fromEvent(event: FileEvent): FileExtraFields =
    event match {
      case c: FileCreated             =>
        FileExtraFields(
          c.storage.iri,
          c.storageType,
          None,
          Some(c.attributes.bytes),
          c.attributes.mediaType,
          Some(c.attributes.origin)
        )
      case u: FileUpdated             =>
        FileExtraFields(
          u.storage.iri,
          u.storageType,
          None,
          Some(u.attributes.bytes),
          u.attributes.mediaType,
          Some(u.attributes.origin)
        )
      case fau: FileAttributesUpdated =>
        FileExtraFields(
          fau.storage.iri,
          fau.storageType,
          None,
          Some(fau.bytes),
          fau.mediaType,
          Some(FileAttributesOrigin.Storage)
        )
      case fta: FileTagAdded          =>
        FileExtraFields(fta.storage.iri, fta.storageType, None, None, None, None)
      case ftd: FileTagDeleted        =>
        FileExtraFields(ftd.storage.iri, ftd.storageType, None, None, None, None)
      case fd: FileDeprecated         =>
        FileExtraFields(fd.storage.iri, fd.storageType, None, None, None, None)
    }

  implicit val fileExtraFieldsEncoder: Encoder.AsObject[FileExtraFields] =
    deriveEncoder[FileExtraFields]
}
