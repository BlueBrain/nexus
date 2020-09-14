package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.AclTargetResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Identity, ResourceF, ResourceRef}
import org.apache.jena.iri.IRI

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
  final def types: Set[IRI] = Set(nxv.AccessControlList)

  /**
    * Converts the state into a resource representation.
    *
    * @param id the resource identifier
    */
  def toResource: AclTargetResource
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

    override val toResource: AclTargetResource =
      ResourceF(
        id = Target.Root,
        rev = rev,
        types = types,
        deprecated = deprecated,
        createdAt = Instant.EPOCH,
        createdBy = Identity.Anonymous,
        updatedAt = Instant.EPOCH,
        updatedBy = Identity.Anonymous,
        schema = schema,
        value = Acl.empty
      )
  }

  /**
    * An existing ACLs state.
    *
    * @param target    the target location for the ACL
    * @param acl       the Access Control List
    * @param rev       the ACLs revision
    * @param createdAt the instant when the resource was created
    * @param createdBy the identity that created the resource
    * @param updatedAt the instant when the resource was last updated
    * @param updatedBy the identity that last updated the resource
    */
  final case class Current(
      target: Target,
      acl: Acl,
      rev: Long,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends AclState {
    override val toResource: AclTargetResource =
      ResourceF(
        id = target,
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

}
