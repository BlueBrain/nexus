package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF}

object OrganizationGen {

  def currentState(
      label: String,
      rev: Long,
      uuid: UUID = UUID.randomUUID(),
      description: Option[String] = None,
      deprecated: Boolean = false
  ): Current =
    Current(
      Label.unsafe(label),
      uuid,
      rev,
      deprecated,
      description,
      Instant.EPOCH,
      Anonymous,
      Instant.EPOCH,
      Anonymous
    )

  def organization(label: String, uuid: UUID = UUID.randomUUID(), description: Option[String] = None): Organization =
    Organization(Label.unsafe(label), uuid, description)

  def resourceFor(
      organization: Organization,
      rev: Long,
      subject: Subject,
      deprecated: Boolean = false
  ): ResourceF[Label, Organization] =
    ResourceF(
      id = organization.label,
      rev = rev,
      types = Set(nxv.Organization),
      deprecated = deprecated,
      createdAt = Instant.EPOCH,
      createdBy = subject,
      updatedAt = Instant.EPOCH,
      updatedBy = subject,
      schema = Latest(schemas.organizations),
      value = organization
    )

}
