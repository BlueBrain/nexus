package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject

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
    * @param base              the base Iri for generated resource IDs ending with ''/'' or ''#''
    * @param vocab             an optional vocabulary for resources with no context ending with ''/'' or ''#''
    * @param subject           the identity associated to this command
    */
  final case class CreateProject(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      description: Option[String],
      apiMappings: Map[String, Iri],
      base: PrefixIRI,
      vocab: PrefixIRI,
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
    * @param base              the base Iri for generated resource IDs ending with ''/'' or ''#''
    * @param vocab             an optional vocabulary for resources with no context ending with ''/'' or ''#''
    * @param rev               the last known revision of the project
    * @param subject           the identity associated to this command
    */
  final case class UpdateProject(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      description: Option[String],
      apiMappings: Map[String, Iri],
      base: PrefixIRI,
      vocab: PrefixIRI,
      rev: Long,
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
    * @param subject           the identity associated to this command
    */
  final case class DeprecateProject(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      rev: Long,
      subject: Subject
  ) extends ProjectCommand
}
