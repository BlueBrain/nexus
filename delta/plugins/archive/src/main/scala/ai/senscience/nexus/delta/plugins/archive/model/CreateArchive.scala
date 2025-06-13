package ai.senscience.nexus.delta.plugins.archive.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

/**
  * Command for the creation of an archive.
  *
  * @param id
  *   the identifier of the archive
  * @param project
  *   the parent project
  * @param value
  *   the archive value
  * @param subject
  *   the identity associated with this command
  */
final case class CreateArchive(
    id: Iri,
    project: ProjectRef,
    value: ArchiveValue,
    subject: Subject
)
