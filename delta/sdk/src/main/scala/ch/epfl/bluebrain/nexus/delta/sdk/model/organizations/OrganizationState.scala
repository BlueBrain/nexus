package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.OrganizationResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris}
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
import scala.annotation.nowarn

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
    * @return
    *   the schema reference that organizations conforms to
    */
  def schema: ResourceRef = Latest(schemas.organizations)

  /**
    * @return
    *   the collection of known types of organizations resources
    */
  def types: Set[Iri] = Set(nxv.Organization)

  private val uris = ResourceUris.organization(label)

  def toResource: OrganizationResource =
    ResourceF(
      id = uris.relativeAccessUri.toIri,
      uris = uris,
      rev = rev.toLong,
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

  @nowarn("cat=unused")
  val serializer: Serializer[Label, OrganizationState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration             = Serializer.circeConfiguration
    implicit val coder: Codec.AsObject[OrganizationState] = deriveConfiguredCodec[OrganizationState]
    Serializer(_.label)
  }

}
