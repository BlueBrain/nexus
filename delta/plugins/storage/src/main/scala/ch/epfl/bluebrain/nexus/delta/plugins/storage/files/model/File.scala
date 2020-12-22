package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRef
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
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
    tags: Map[TagLabel, Long]
)
