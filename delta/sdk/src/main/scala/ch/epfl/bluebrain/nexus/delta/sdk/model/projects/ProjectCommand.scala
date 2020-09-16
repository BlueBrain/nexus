package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import org.apache.jena.iri.IRI

/**
  * Enumeration of Project command types.
  */
sealed trait ProjectCommand extends Product with Serializable {

  /**
    * @return the project label
    */
  def label: Label

  /**
    * @return the project unique identifier
    */
  def uuid: UUID

  /**
    * @return the parent organization label
    */
  def organizationLabel: Label

  /**
    * @return the parent organization uuid
    */
  def organizationUuid: UUID

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
    * @param label             the project label
    * @param uuid              the project unique identifier
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param description       an optional project description
    * @param apiMappings       the API mappings
    * @param base              the base IRI for generated resource IDs
    * @param vocab             an optional vocabulary for resources with no context
    * @param instant           the timestamp associated to this command
    * @param subject           the identity associated to this command
    */
  final case class CreateProject(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      description: Option[String],
      apiMappings: Map[String, IRI],
      base: IRI,
      vocab: IRI,
      instant: Instant,
      subject: Subject
  ) extends ProjectCommand

  /**
    * Command that signals the intent to update a project.
    *
    * @param label             the project label
    * @param uuid              the project unique identifier
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param description       an optional project description
    * @param apiMappings       the API mappings
    * @param base              the base IRI for generated resource IDs
    * @param vocab             an optional vocabulary for resources with no context
    * @param rev               the last known revision of the project
    * @param instant           the timestamp associated to this command
    * @param subject           the identity associated to this command
    */
  final case class UpdateProject(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      description: Option[String],
      apiMappings: Map[String, IRI],
      base: IRI,
      vocab: IRI,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ProjectCommand

  /**
    * Command that signals the intent to deprecate a project.
    *
    * @param label             the project label
    * @param uuid              the project unique identifier
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param rev               the last known revision of the project
    * @param instant           the timestamp associated to this command
    * @param subject           the identity associated to this command
    */
  final case class DeprecateProject(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ProjectCommand
}
