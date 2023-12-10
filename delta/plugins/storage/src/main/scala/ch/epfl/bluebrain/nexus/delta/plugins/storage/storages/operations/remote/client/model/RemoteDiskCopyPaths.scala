package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model

import akka.http.scaladsl.model.Uri.Path
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

final case class RemoteDiskCopyPaths(
    sourceBucket: Label,
    sourcePath: Path,
    destPath: Path
)
