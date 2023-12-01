package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

/**
  * Enumeration of Project command types.
  */
sealed trait ProjectCommand extends Product with Serializable {

  /**
    * @return
    *   the project ref
    */
  def ref: ProjectRef

  /**
    * @return
    *   the last known revision of the project
    */
  def rev: Int

  /**
    * @return
    *   the identity associated to this command
    */
  def subject: Subject
}

object ProjectCommand {

  /**
    * Command that signals the intent to create a new project.
    *
    * @param ref
    *   the project ref
    * @param fields
    *   the payload submitted by the user
    * @param subject
    *   the identity associated to this command
    */
  final case class CreateProject(
      ref: ProjectRef,
      fields: ProjectFields,
      subject: Subject
  ) extends ProjectCommand {
    override def rev: Int = 0
  }

  /**
    * Command that signals the intent to update a project.
    *
    * @param ref
    *   the project ref
    * @param fields
    *   the payload submitted by the user
    * @param rev
    *   the last known revision of the project
    * @param subject
    *   the identity associated to this command
    */
  final case class UpdateProject(
      ref: ProjectRef,
      fields: ProjectFields,
      rev: Int,
      subject: Subject
  ) extends ProjectCommand

  /**
    * Command that signals the intent to deprecate a project.
    *
    * @param ref
    *   the project ref
    * @param rev
    *   the last known revision of the project
    * @param subject
    *   the identity associated to this command
    */
  final case class DeprecateProject(ref: ProjectRef, rev: Int, subject: Subject) extends ProjectCommand

  /**
    * Command that signals the intent to undeprecate a project.
    *
    * @param ref
    *   the project ref
    * @param rev
    *   the last known revision of the project
    * @param subject
    *   the identity associated to this command
    */
  final case class UndeprecateProject(ref: ProjectRef, rev: Int, subject: Subject) extends ProjectCommand

  /**
    * Command that signals the intent to delete a project.
    *
    * @param ref
    *   the project ref
    * @param rev
    *   the last known revision of the project
    * @param subject
    *   the identity associated to this command
    */
  final case class DeleteProject(ref: ProjectRef, rev: Int, subject: Subject) extends ProjectCommand
}
