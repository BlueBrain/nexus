package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import org.apache.jena.iri.IRI

/**
  * A project representation.
  *
  * @param label             the project label
  * @param uuid              the project unique identifier
  * @param organizationLabel the parent organization label
  * @param organizationUuid  the parent organization unique identifier
  * @param description       an optional description
  * @param apiMappings       the API mappings
  * @param base              the base IRI for generated resource IDs
  * @param vocab             an optional vocabulary for resources with no context
  */
final case class Project(
    label: Label,
    uuid: UUID,
    organizationLabel: Label,
    organizationUuid: UUID,
    description: Option[String],
    apiMappings: Map[String, IRI],
    base: IRI,
    vocab: IRI
) {

  /**
    * @return a project label reference containing the parent organization label
    */
  def ref: ProjectRef =
    ProjectRef(organizationLabel, label)
}
