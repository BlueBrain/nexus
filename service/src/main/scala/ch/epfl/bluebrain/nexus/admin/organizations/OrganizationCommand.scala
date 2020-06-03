package ch.epfl.bluebrain.nexus.admin.organizations

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject

/**
  * Enumeration of Organization collection command types.
  */
sealed trait OrganizationCommand extends Product with Serializable {

  /**
    * @return ID of the organization
    */
  def id: UUID

  /**
    * @return the instant when this command was created
    */
  def instant: Instant

  /**
    * @return the subject which created this command
    */
  def subject: Subject
}

object OrganizationCommand {

  /**
    * An intent to create an organization.
    * @param id           ID of the organization
    * @param label        the organization label
    * @param description  an optional description of the organization
    * @param instant      the instant when this command was created
    * @param subject      the subject which created this command.
    */
  final case class CreateOrganization(
      id: UUID,
      label: String,
      description: Option[String],
      instant: Instant,
      subject: Subject
  ) extends OrganizationCommand

  /**
    * An intent to create an organization.
    *
    * @param id           ID of the organization
    * @param rev          the revision to update
    * @param label        the organization label
    * @param description  an optional description of the organization
    * @param instant      the instant when this command was created
    * @param subject      the subject which created this command.
    */
  final case class UpdateOrganization(
      id: UUID,
      rev: Long,
      label: String,
      description: Option[String],
      instant: Instant,
      subject: Subject
  ) extends OrganizationCommand

  /**
    * An intent to deprecate an organization.
    *
    * @param id           ID of the organization
    * @param rev          the revision to deprecate
    * @param instant      the instant when this command was created
    * @param subject      the subject which created this command.
    */
  final case class DeprecateOrganization(id: UUID, rev: Long, instant: Instant, subject: Subject)
      extends OrganizationCommand
}
