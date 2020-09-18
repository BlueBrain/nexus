package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label

/**
  * Enumeration of Project rejection types.
  *
 * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class ProjectRejection(val reason: String) extends Product with Serializable

object ProjectRejection {

  /**
    * Rejection returned when a subject intends to retrieve a project at a specific revision, but the provided revision
    * does not exist.
    *
   * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends ProjectRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Signals an error while decoding a project JSON payload.
    *
    * @param message human readable error details
    */
  final case class InvalidProjectFormat(message: String)
      extends ProjectRejection("The project json representation is incorrectly formatted.")

  /**
    * Signals that a project cannot be created because one with the same identifier already exists.
    */
  final case class ProjectAlreadyExists(projectRef: ProjectRef)
      extends ProjectRejection(s"Project '$projectRef' already exists.")

  /**
    * Signals that an operation on a project cannot be performed due to the fact that the referenced project does not exist.
    */
  final case class ProjectNotFound private (override val reason: String) extends ProjectRejection(reason)
  object ProjectNotFound {
    def apply(uuid: UUID): ProjectNotFound             =
      ProjectNotFound(s"Project with uuid '${uuid.toString.toLowerCase()}' not found.")
    def apply(projectRef: ProjectRef): ProjectNotFound =
      ProjectNotFound(s"Project with label '$projectRef' not found.")
  }

  /**
    * Signals that an operation on a project cannot be performed due to the fact that the referenced parent organization
    * does not exist.
    */
  final case class OrganizationNotFound(label: Label)
      extends ProjectRejection(s"Organization with label '$label' not found.")

  /**
    * Signals that an operation on a project cannot be performed due to the fact that the referenced parent organization
    * is deprecated.
    */
  final case class OrganizationIsDeprecated(label: Label)
      extends ProjectRejection(s"Organization with label '$label' is deprecated.")

  /**
    * Signals and attempt to update/deprecate an organization that is already deprecated.
    */
  final case class ProjectIsDeprecated private (override val reason: String) extends ProjectRejection(reason)
  object ProjectIsDeprecated {
    def apply(uuid: UUID): ProjectIsDeprecated             =
      ProjectIsDeprecated(s"Project with uuid '${uuid.toString.toLowerCase()}' is deprecated.")
    def apply(projectRef: ProjectRef): ProjectIsDeprecated =
      ProjectIsDeprecated(s"Project with label '$projectRef' is deprecated.")
  }

  /**
    * Signals that a project update cannot be performed due to an incorrect revision provided.
    *
    * @param expected   latest know revision
    * @param provided the provided revision
    */
  final case class IncorrectRev(expected: Long, provided: Long)
      extends ProjectRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the project may have been updated since last seen."
      )
}
