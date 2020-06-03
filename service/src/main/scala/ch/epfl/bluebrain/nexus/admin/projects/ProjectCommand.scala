package ch.epfl.bluebrain.nexus.admin.projects

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

sealed trait ProjectCommand extends Product with Serializable {

  /**
    * @return the permanent identifier for the project
    */
  def id: UUID

  /**
    * @return the timestamp associated to this command
    */
  def instant: Instant

  /**
    * @return the identity associated to this command
    */
  def subject: Subject
}

object ProjectCommand {

  /**
    * Command that signals the intent to create a new project.
    *
    * @param id                the permanent identifier for the project
    * @param label             the label (segment) of the project
    * @param organizationUuid  the permanent identifier for the parent organization
    * @param organizationLabel the parent organization label
    * @param description       an optional project description
    * @param apiMappings       the API mappings
    * @param base              the base IRI for generated resource IDs
    * @param vocab             an optional vocabulary for resources with no context
    * @param instant           the timestamp associated to this command
    * @param subject           the identity associated to this command
    */
  final case class CreateProject(
      id: UUID,
      label: String,
      organizationUuid: UUID,
      organizationLabel: String,
      description: Option[String],
      apiMappings: Map[String, AbsoluteIri],
      base: AbsoluteIri,
      vocab: AbsoluteIri,
      instant: Instant,
      subject: Subject
  ) extends ProjectCommand

  /**
    * Command that signals the intent to update a project.
    *
    * @param id          the permanent identifier for the project
    * @param label       the label (segment) of the resource
    * @param description an optional project description
    * @param apiMappings the API mappings
    * @param base        the base IRI for generated resource IDs
    * @param vocab       an optional vocabulary for resources with no context
    * @param rev         the last known revision of the project
    * @param instant     the timestamp associated to this command
    * @param subject     the identity associated to this command
    */
  final case class UpdateProject(
      id: UUID,
      label: String,
      description: Option[String],
      apiMappings: Map[String, AbsoluteIri],
      base: AbsoluteIri,
      vocab: AbsoluteIri,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ProjectCommand

  /**
    * Command that signals the intent to deprecate a project.
    *
    * @param id      the permanent identifier for the project
    * @param rev     the last known revision of the project
    * @param instant the timestamp associated to this command
    * @param subject the identity associated to this command
    */
  final case class DeprecateProject(id: UUID, rev: Long, instant: Instant, subject: Subject) extends ProjectCommand
}
