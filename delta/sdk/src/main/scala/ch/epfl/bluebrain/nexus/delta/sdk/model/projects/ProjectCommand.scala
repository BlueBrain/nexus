package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject

/**
  * Enumeration of Project command types.
  */
sealed trait ProjectCommand extends Product with Serializable {

  /**
    * @return the project ref
    */
  def ref: ProjectRef

  /**
    * @return the identity associated to this command
    */
  def subject: Subject
}

object ProjectCommand {

  /**
    * Command that signals the intent to create a new project.
    *
    * @param ref               the project ref
    * @param description       an optional project description
    * @param apiMappings       the API mappings
    * @param base              the base Iri for generated resource IDs ending with ''/'' or ''#''
    * @param vocab             an optional vocabulary for resources with no context ending with ''/'' or ''#''
    * @param subject           the identity associated to this command
    */
  final case class CreateProject(
      ref: ProjectRef,
      description: Option[String],
      apiMappings: ApiMappings,
      base: PrefixIri,
      vocab: PrefixIri,
      subject: Subject
  ) extends ProjectCommand

  /**
    * Command that signals the intent to update a project.
    *
    * @param ref               the project ref
    * @param description       an optional project description
    * @param apiMappings       the API mappings
    * @param base              the base Iri for generated resource IDs ending with ''/'' or ''#''
    * @param vocab             an optional vocabulary for resources with no context ending with ''/'' or ''#''
    * @param rev               the last known revision of the project
    * @param subject           the identity associated to this command
    */
  final case class UpdateProject(
      ref: ProjectRef,
      description: Option[String],
      apiMappings: ApiMappings,
      base: PrefixIri,
      vocab: PrefixIri,
      rev: Long,
      subject: Subject
  ) extends ProjectCommand

  /**
    * Command that signals the intent to deprecate a project.
    *
    * @param ref               the project ref
    * @param rev               the last known revision of the project
    * @param subject           the identity associated to this command
    */
  final case class DeprecateProject(ref: ProjectRef, rev: Long, subject: Subject) extends ProjectCommand
}
