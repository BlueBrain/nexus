package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag

/**
  * Details for the files we're creating in the copy
  *
  * @param project
  *   Orgnization and project for the new file
  * @param storage
  *   Optional storage for the new file which must have the same type as the source file's storage
  * @param tag
  *   Optional tag to create the new file with
  */
final case class CopyFileDestination(
    project: ProjectRef,
    storage: Option[IdSegment],
    tag: Option[UserTag]
)
