package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.FileResource

final case class FailureSummary(
    source: CopyFileSource,
    reason: String // TODO use ADT
)

final case class CopyFilesResponse(
    successes: List[FileResource],
    failures: List[FailureSummary]
)
