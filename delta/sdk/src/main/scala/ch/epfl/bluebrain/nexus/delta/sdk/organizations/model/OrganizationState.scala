package ch.epfl.bluebrain.nexus.delta.sdk.organizations.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.OrganizationResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceAccess, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant
import java.util.UUID

/**
  * Enumeration of organization states.
  */

final case class OrganizationState(
    label: Label,
    uuid: UUID,
    rev: Int,
    deprecated: Boolean,
    description: Option[String],
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends GlobalState {

  /**
    * The relative [[Iri]] of the organization
    */
  def id: Iri = Organizations.encodeId(label)

  /**
    * @return
    *   the schema reference that organizations conforms to
    */
  def schema: ResourceRef = Latest(schemas.organizations)

  /**
    * @return
    *   the collection of known types of organizations resources
    */
  def types: Set[Iri] = Set(nxv.Organization)

  def toResource: OrganizationResource =
    ResourceF(
      id = id,
      access = ResourceAccess.organization(label),
      rev = rev,
      types = types,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = Organization(label, uuid, description)
    )
}

object OrganizationState {

  val serializer: Serializer[Label, OrganizationState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database.*
    implicit val configuration: Configuration             = Serializer.circeConfiguration
    implicit val coder: Codec.AsObject[OrganizationState] = deriveConfiguredCodec[OrganizationState]
    Serializer(Organizations.encodeId)
  }

}
