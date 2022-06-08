package ch.epfl.bluebrain.nexus.delta.sdk.organizations.model

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

/**
  * Enumeration of Organization collection command types.
  */
sealed trait OrganizationCommand extends Product with Serializable {

  /**
    * @return
    *   the organization Label
    */
  def label: Label

  /**
    * @return
    *   the subject which created this command
    */
  def subject: Subject
}

object OrganizationCommand {

  /**
    * An intent to create an organization.
    * @param label
    *   the organization label
    * @param description
    *   an optional description of the organization
    * @param subject
    *   the subject which created this command.
    */
  final case class CreateOrganization(
      label: Label,
      description: Option[String],
      subject: Subject
  ) extends OrganizationCommand

  /**
    * An intent to create an organization.
    *
    * @param label
    *   the organization label
    * @param rev
    *   the revision to update
    * @param description
    *   an optional description of the organization
    * @param subject
    *   the subject which created this command.
    */
  final case class UpdateOrganization(
      label: Label,
      rev: Int,
      description: Option[String],
      subject: Subject
  ) extends OrganizationCommand

  /**
    * An intent to deprecate an organization.
    *
    * @param label
    *   the organization label
    * @param rev
    *   the revision to deprecate
    * @param subject
    *   the subject which created this command.
    */
  final case class DeprecateOrganization(
      label: Label,
      rev: Int,
      subject: Subject
  ) extends OrganizationCommand
}
