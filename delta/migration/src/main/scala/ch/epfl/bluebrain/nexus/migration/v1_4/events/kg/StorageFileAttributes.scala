package ch.epfl.bluebrain.nexus.migration.v1_4.events.kg

import akka.http.scaladsl.model.{ContentType, Uri}

final case class StorageFileAttributes(location: Uri, bytes: Long, digest: Digest, mediaType: ContentType)
