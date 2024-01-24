package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.LimitedFileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage

final case class DiskCopyDetails(
    destStorage: DiskStorage,
    sourceAttributes: LimitedFileAttributes
)
