package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin

case class FileMetadata(bytes: Long, digest: Digest, origin: FileAttributesOrigin)

object FileMetadata {
  def from(attributes: FileAttributes): FileMetadata = {
    FileMetadata(attributes.bytes, attributes.digest, attributes.origin)
  }
}
