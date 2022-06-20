package ch.epfl.bluebrain.nexus.delta.sdk.acls.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import io.circe.{Codec, Encoder}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.syntax.EncoderOps

import java.time.Instant
import scala.annotation.nowarn

/**
  * An existing ACLs state.
  *
  * @param acl
  *   the Access Control List
  * @param rev
  *   the ACLs revision
  * @param createdAt
  *   the instant when the resource was created
  * @param createdBy
  *   the identity that created the resource
  * @param updatedAt
  *   the instant when the resource was last updated
  * @param updatedBy
  *   the identity that last updated the resource
  */
final case class AclState(
    acl: Acl,
    rev: Int,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends GlobalState {

  /**
    * @return
    *   the current deprecation status (always false for acls)
    */
  def deprecated: Boolean = false

  /**
    * @return
    *   the schema reference that acls conforms to
    */
  def schema: ResourceRef = Latest(schemas.acls)

  /**
    * @return
    *   the collection of known types of acls resources
    */
  def types: Set[Iri] = Set(nxv.AccessControlList)

  def toResource: AclResource = {
    val uris = ResourceUris.acl(acl.address)
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
      value = acl
    )
  }

}

object AclState {

  @nowarn("cat=unused")
  val serializer: Serializer[AclAddress, AclState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    import Acl.Database._
    implicit val configuration: Configuration    = Serializer.circeConfiguration
    implicit val coder: Codec.AsObject[AclState] = Codec.AsObject.from(
      deriveConfiguredDecoder[AclState],
      Encoder.AsObject.instance { state =>
        deriveConfiguredEncoder[AclState].mapJsonObject(_.add("address", state.acl.address.asJson)).encodeObject(state)
      }
    )
    Serializer(_.acl.address)
  }

  def initial(permissions: Set[Permission]): AclState = AclState(
    Acl(AclAddress.Root, Identity.Anonymous -> permissions),
    0,
    Instant.EPOCH,
    Identity.Anonymous,
    Instant.EPOCH,
    Identity.Anonymous
  )

}
