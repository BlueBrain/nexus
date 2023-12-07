package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

final case class RemoteDiskCopyDetails(
    destStorage: RemoteDiskStorage,
    destinationDesc: FileDescription,
    sourceBucket: Label,
    sourceAttributes: FileAttributes
)
