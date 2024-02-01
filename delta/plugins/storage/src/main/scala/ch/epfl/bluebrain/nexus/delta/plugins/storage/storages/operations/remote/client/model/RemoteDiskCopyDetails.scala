package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileDescription, FileMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

import akka.http.scaladsl.model.Uri.Path
import java.util.UUID

final case class RemoteDiskCopyDetails(
    destUuid: UUID,
    destStorage: RemoteDiskStorage,
    sourcePath: Path,
    sourceBucket: Label,
    sourceMetadata: FileMetadata,
    sourceUserSuppliedMetadata: FileDescription
)
