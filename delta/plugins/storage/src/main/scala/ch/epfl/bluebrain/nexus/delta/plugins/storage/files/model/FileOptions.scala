package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag

/**
  * Optional parameters used when creating / updating files and file links
  *
  * @param storageId
  *   the optional storage identifier to expand as the id of the storage. When None, the default storage is used
  * @param filename
  *   the optional filename to use when creating / updating files and file links
  * @param mediaType
  *   the optional media type to use when creating / updating files and file links
  * @param tag
  *   the optional tag used when creating a file or file link, attached to the first revision
  */
final case class FileOptions(
    storageId: Option[IdSegment],
    filename: Option[String],
    mediaType: Option[ContentType],
    tag: Option[UserTag]
)

object FileOptions {
  val empty: FileOptions                                                                                       = FileOptions(None, None, None, None)
  def apply(): FileOptions                                                                                     = empty
  def apply(storage: IdSegment): FileOptions                                                                   = apply(Some(storage), None)
  def apply(tag: UserTag): FileOptions                                                                         = apply(None, Some(tag))
  def apply(storage: IdSegment, tag: UserTag): FileOptions                                                     = FileOptions(Some(storage), Some(tag))
  def apply(storage: IdSegment, mediaType: ContentType): FileOptions                                           = FileOptions(Some(storage), None, Some(mediaType))
  def apply(storage: Option[IdSegment], tag: Option[UserTag]): FileOptions                                     = FileOptions(storage, None, None, tag)
  def apply(storage: Option[IdSegment], filename: Option[String], mediaType: Option[ContentType]): FileOptions =
    FileOptions(storage, filename, mediaType, None)
  def apply(storage: IdSegment, filename: String, tag: UserTag): FileOptions                                   =
    FileOptions(Some(storage), Some(filename), None, Some(tag))
}
