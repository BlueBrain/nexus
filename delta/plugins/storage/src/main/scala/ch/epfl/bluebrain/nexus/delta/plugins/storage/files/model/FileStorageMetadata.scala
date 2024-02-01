package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin

import java.util.UUID

case class FileStorageMetadata(
    uuid: UUID,
    bytes: Long,
    digest: Digest,
    origin: FileAttributesOrigin,
    location: Uri,
    path: Path
)
