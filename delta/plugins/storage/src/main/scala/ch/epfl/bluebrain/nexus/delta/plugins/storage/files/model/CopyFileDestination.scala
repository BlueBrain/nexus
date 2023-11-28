package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag

/**
  * Details of the file we're creating in the copy
  *
  * @param project
  *   Orgnization and project for the new file
  * @param fileId
  *   Optional identifier for the new file
  * @param storage
  *   Optional storage for the new file which must have the same type as the source file's storage
  * @param tag
  *   Optional tag to create the new file with
  * @param filename
  *   Optional filename for the new file. If omitted, the source filename will be used
  */
final case class CopyFileDestination(
    project: ProjectRef,
    fileId: Option[IdSegment],
    storage: Option[IdSegment],
    tag: Option[UserTag],
    filename: Option[String]
)
