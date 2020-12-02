package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Lens, OrganizationResource}

/**
  * Enumeration of organization states.
  */

sealed trait OrganizationState extends Product with Serializable {

  /**
    * @return the current state revision
    */
  def rev: Long

  /**
    * @return the current deprecation status of the organization
    */
  def deprecated: Boolean

  /**
    * @return the schema reference that organizations conforms to
    */
  final def schema: ResourceRef = Latest(schemas.organizations)

  /**
    * @return the collection of known types of organizations resources
    */
  final def types: Set[Iri] = Set(nxv.Organization)

  /**
    * Converts the state into a resource representation.
    */
  def toResource: Option[OrganizationResource]
}

object OrganizationState {

  /**
    * Initial state type.
    */
  type Initial = Initial.type

  /**
    * Initial organizations state.
    */
  final case object Initial extends OrganizationState {
    override val rev: Long = 0L

    override val deprecated: Boolean = false

    override val toResource: Option[OrganizationResource] = None
  }

  /**
    * Initial organization state.
    *
    * @param label        the organization label
    * @param uuid         the organization UUID
    * @param rev          the organization revision
    * @param deprecated   the deprecation status of the organization
    * @param description  an optional description of the organization
    * @param createdAt    the instant when the organization was created
    * @param createdBy    the identity that created the organization
    * @param updatedAt    the instant when the organization was last updated
    * @param updatedBy    the identity that last updated the organization
    */
  final case class Current(
      label: Label,
      uuid: UUID,
      rev: Long,
      deprecated: Boolean,
      description: Option[String],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends OrganizationState {

    private val uris = ResourceUris.organization(label)

    override def toResource: Option[OrganizationResource] =
      Some(
        ResourceF(
          id = uris.relativeAccessUri.toIri,
          uris = uris,
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
      )
  }

  implicit val revisionLens: Lens[OrganizationState, Long] = (s: OrganizationState) => s.rev

}
