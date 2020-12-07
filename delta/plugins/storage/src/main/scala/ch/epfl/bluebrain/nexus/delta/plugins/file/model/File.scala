package ch.epfl.bluebrain.nexus.delta.plugins.file.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageRef
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * A representation of a file information
  *
  * @param id         the file identifier
  * @param project    the project where the file belongs
  * @param storage    the reference to the used storage
  * @param attributes the file attributes
  * @param tags       the file tags
  */
final case class File(
    id: Iri,
    project: ProjectRef,
    storage: StorageRef,
    attributes: FileAttributes,
    tags: Map[Label, Long]
)
