package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

final case class CopyFileDetails(
    destinationDesc: FileDescription,
    sourceAttributes: FileAttributes
)
