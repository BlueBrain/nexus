package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.PermissionsResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Identity, ResourceF}
import org.apache.jena.iri.IRI

/**
  * Enumeration of Permissions states.
  */
sealed trait PermissionsState extends Product with Serializable {

  /**
    * @return the current state revision
    */
  def rev: Long

  /**
    * @return the current deprecation status (always false for permissions)
    */
  def deprecated: Boolean = false

  /**
    * Converts the state into a resource representation.
    *
    * @param id      the resource identifier
    * @param minimum minimum set of permissions (static configuration)
    */
  def toResource(id: IRI, minimum: Set[Permission]): PermissionsResource
}

object PermissionsState {

  /**
    * Initial state type.
    */
  type Initial = Initial.type

  /**
    * Initial state for the permission set.
    */
  final case object Initial extends PermissionsState {
    override val rev: Long = 0L

    override def toResource(id: IRI, minimum: Set[Permission]): PermissionsResource =
      ResourceF(
        id = id,
        rev = rev,
        types = Set(nxv.Permissions),
        deprecated = deprecated,
        createdAt = Instant.EPOCH,
        createdBy = Identity.Anonymous,
        updatedAt = Instant.EPOCH,
        updatedBy = Identity.Anonymous,
        schema = Latest(schemas.permissions),
        value = minimum
      )
  }

  /**
    * The "current" state for the permission set, available once at least one event was emitted.
    *
    * @param rev         the current state revision
    * @param permissions the permission set
    * @param createdAt   the instant when the resource was created
    * @param createdBy   the subject that created the resource
    * @param updatedAt   the instant when the resource was last updated
    * @param updatedBy   the subject that last updated the resource
    */
  final case class Current(
      rev: Long,
      permissions: Set[Permission],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends PermissionsState {

    override def toResource(id: IRI, minimum: Set[Permission]): PermissionsResource =
      ResourceF(
        id = id,
        rev = rev,
        types = Set(nxv.Permissions),
        deprecated = deprecated,
        createdAt = createdAt,
        createdBy = createdBy,
        updatedAt = updatedAt,
        updatedBy = updatedBy,
        schema = Latest(schemas.permissions),
        value = permissions ++ minimum
      )
  }
}
