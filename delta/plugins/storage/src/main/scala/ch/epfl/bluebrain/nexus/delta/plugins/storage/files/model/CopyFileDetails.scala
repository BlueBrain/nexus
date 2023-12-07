package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage

final case class CopyFileDetails(
    destinationDesc: FileDescription,
    sourceAttributes: FileAttributes,
    sourceStorage: Storage
)
