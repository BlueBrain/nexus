package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import java.util.UUID

/**
  * Enumeration of organization rejection types.
  *
 * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class OrganizationRejection(val reason: String) extends Product with Serializable

object OrganizationRejection {

  /**
    * Signals the the organization already exists.
    */
  final case class OrganizationAlreadyExists(label: String)
      extends OrganizationRejection(s"Organization with label '$label' already exists.")

  /**
    * Signals that the organization does not exist.
    */
  final case class OrganizationNotFound private (override val reason: String) extends OrganizationRejection(reason)

  object OrganizationNotFound {
    def apply(uuid: UUID): OrganizationNotFound =
      OrganizationNotFound(s"Organization with uuid '${uuid.toString.toLowerCase()}' not found.")

    def apply(label: String): OrganizationNotFound =
      new OrganizationNotFound(s"Organization with label '$label' not found.")
  }

  /**
    * Signals that the provided revision does not match the latest revision
    *
    * @param expected latest know revision
    * @param provided provided revision
    */
  final case class IncorrectRev(expected: Long, provided: Long)
      extends OrganizationRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the organization may have been updated since last seen."
      )

  /**
    * Signals an attempt to retrieve an organization at a specific revision when the provided revision does not exist.
    *
   * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends OrganizationRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

}
