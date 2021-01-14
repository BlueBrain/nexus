package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType

/**
  * Holds metadata information related to the file computed attributes.
  *
  * @param mediaType the media type of the file
  * @param bytes     the size of the file file in bytes
  * @param digest    the digest information of the file
  */
final case class ComputedFileAttributes(
    mediaType: ContentType,
    bytes: Long,
    digest: Digest
)
