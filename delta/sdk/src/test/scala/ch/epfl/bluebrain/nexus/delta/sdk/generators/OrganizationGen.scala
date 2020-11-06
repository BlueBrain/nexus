package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.OrganizationResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationState.Current
import org.scalatest.OptionValues

object OrganizationGen extends OptionValues {

  def currentState(
      label: String,
      rev: Long,
      uuid: UUID = UUID.randomUUID(),
      description: Option[String] = None,
      deprecated: Boolean = false,
      subject: Subject = Anonymous
  ): Current =
    Current(
      Label.unsafe(label),
      uuid,
      rev,
      deprecated,
      description,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    )

  def organization(label: String, uuid: UUID = UUID.randomUUID(), description: Option[String] = None): Organization =
    Organization(Label.unsafe(label), uuid, description)

  def resourceFor(
      organization: Organization,
      rev: Long,
      subject: Subject = Identity.Anonymous,
      deprecated: Boolean = false
  ): OrganizationResource =
    currentState(
      organization.label.value,
      rev,
      organization.uuid,
      organization.description,
      deprecated,
      subject
    ).toResource.value

}
