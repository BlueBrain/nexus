package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.AclTargetResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Identity, ResourceF}
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
  def deprecated: Boolean = false

  /**
    * Converts the state into a resource representation.
    *
    * @param id the resource identifier
    */
  def toResource(id: IRI): AclTargetResource
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

    override def toResource(id: IRI): AclTargetResource =
      ResourceF(
        id = id,
        rev = rev,
        types = Set(nxv.AccessControlList),
        deprecated = deprecated,
        createdAt = Instant.EPOCH,
        createdBy = Identity.Anonymous,
        updatedAt = Instant.EPOCH,
        updatedBy = Identity.Anonymous,
        schema = Latest(schemas.acls),
        value = Target.Root -> Acl.empty
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
    override def toResource(id: IRI): AclTargetResource =
      ResourceF(
        id = id,
        rev = rev,
        types = Set(nxv.AccessControlList),
        deprecated = deprecated,
        createdAt = Instant.EPOCH,
        createdBy = Identity.Anonymous,
        updatedAt = Instant.EPOCH,
        updatedBy = Identity.Anonymous,
        schema = Latest(schemas.acls),
        value = target -> acl
      )
  }

}
