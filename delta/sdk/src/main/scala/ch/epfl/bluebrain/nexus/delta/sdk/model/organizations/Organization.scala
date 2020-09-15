package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label

/**
  * Representation of an organization.
  *
  * @param label        the label of the organization
  * @param uuid         the UUID of the organization
  * @param description  an optional description of the organization
  */
final case class Organization(label: Label, uuid: UUID, description: Option[String]) {
  override def toString: String = label.toString
}
