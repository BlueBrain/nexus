package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{AccessUrl, ResourceF, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{AclResource, Lens}

/**
  * Enumeration of ACLs states.
  */
sealed trait AclState extends Product with Serializable {

  /**
    * @return the current state revision
    */
  def rev: Long

  /**
    * @return the current deprecation status (always false for acls)
    */
  final def deprecated: Boolean = false

  /**
    * @return the schema reference that acls conforms to
    */
  final def schema: ResourceRef = Latest(schemas.acls)

  /**
    * @return the collection of known types of acls resources
    */
  final def types: Set[Iri] = Set(nxv.AccessControlList)

  /**
    * Converts the state into a resource representation.
    */
  def toResource: Option[AclResource]
}

object AclState {

  /**
    * Initial state type.
    */
  type Initial = Initial.type

  /**
    * Initial state for the permission set.
    */
  final case object Initial extends AclState {
    override val rev: Long = 0L

    override val toResource: Option[AclResource] = None
  }

  /**
    * An existing ACLs state.
    *
    * @param acl       the Access Control List
    * @param rev       the ACLs revision
    * @param createdAt the instant when the resource was created
    * @param createdBy the identity that created the resource
    * @param updatedAt the instant when the resource was last updated
    * @param updatedBy the identity that last updated the resource
    */
  final case class Current(
      acl: Acl,
      rev: Long,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends AclState {
    override def toResource: Option[AclResource] =
      Some(asResource)

    def asResource: AclResource =
      ResourceF(
        id = AccessUrl.acl(acl.address)(_).iri,
        accessUrl = AccessUrl.acl(acl.address)(_),
        rev = rev,
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

  implicit val revisionLens: Lens[AclState, Long] = (s: AclState) => s.rev

}
