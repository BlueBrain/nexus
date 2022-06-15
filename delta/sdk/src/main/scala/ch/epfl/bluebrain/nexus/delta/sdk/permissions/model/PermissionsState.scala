package ch.epfl.bluebrain.nexus.delta.sdk.permissions.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.PermissionsResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.{model, Permissions}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant
import scala.annotation.nowarn

/**
  * State for permissions
  */
final case class PermissionsState(
    rev: Int,
    permissions: Set[Permission],
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends GlobalState {

  override def deprecated: Boolean = false

  /**
    * @return
    *   the schema reference that permissions conforms to
    */
  def schema: ResourceRef = Latest(schemas.permissions)

  /**
    * @return
    *   the collection of known types of permissions resources
    */
  def types: Set[Iri] = Set(nxv.Permissions)

  def toResource(minimum: Set[Permission]): PermissionsResource = {
    ResourceF(
      id = ResourceUris.permissions.relativeAccessUri.toIri,
      uris = ResourceUris.permissions,
      rev = rev.toLong,
      types = types,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = model.PermissionSet(permissions ++ minimum)
    )
  }
}

object PermissionsState {

  def initial(minimum: Set[Permission]): PermissionsState = PermissionsState(
    0,
    minimum,
    Instant.EPOCH,
    Anonymous,
    Instant.EPOCH,
    Anonymous
  )

  @nowarn("cat=unused")
  val serializer: Serializer[Label, PermissionsState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration            = Serializer.circeConfiguration
    implicit val coder: Codec.AsObject[PermissionsState] = deriveConfiguredCodec[PermissionsState]
    Serializer(_ => Permissions.entityId)
  }

}
