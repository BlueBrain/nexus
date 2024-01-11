package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.FileUserMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

final case class RemoteDiskCopyDetails(
    destStorage: RemoteDiskStorage,
    destinationDesc: FileDescription,
    sourceBucket: Label,
    sourceAttributes: FileAttributes,
    sourceMetadata: Option[FileUserMetadata]
)
