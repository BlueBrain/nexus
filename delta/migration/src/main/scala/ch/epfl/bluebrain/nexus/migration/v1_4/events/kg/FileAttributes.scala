package ch.epfl.bluebrain.nexus.migration.v1_4.events.kg

import akka.http.scaladsl.model.{ContentType, Uri}
import akka.http.scaladsl.model.Uri.Path

import java.util.UUID

final case class FileAttributes(
    uuid: UUID,
    location: Uri,
    path: Path,
    filename: String,
    mediaType: ContentType,
    bytes: Long,
    digest: Digest
)
