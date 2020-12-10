package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.{ContentType, Uri}
import akka.http.scaladsl.model.Uri.Path

import java.util.UUID

/**
  * Holds all the metadata information related to the file.
  *
  * @param uuid      the unique id that identifies this file.
  * @param location  the absolute location where the file gets stored
  * @param path      the relative path (from the storage) where the file gets stored
  * @param filename  the original filename of the file
  * @param mediaType the media type of the file
  * @param bytes     the size of the file file in bytes
  * @param digest    the digest information of the file
  */
final case class FileAttributes(
    uuid: UUID,
    location: Uri,
    path: Path,
    filename: String,
    mediaType: ContentType,
    bytes: Long,
    digest: Digest
)
