package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.FileUserMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage

final case class DiskCopyDetails(
    destStorage: DiskStorage,
    destinationDesc: FileDescription,
    sourceAttributes: FileAttributes,
    sourceMetadata: Option[FileUserMetadata]
)
