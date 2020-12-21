package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.{DiskStorage, RemoteDiskStorage, S3Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{schemas, StorageResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.Lens
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef, ResourceUris, TagLabel}
import io.circe.Json

import java.time.Instant

/**
  * Enumeration of Storage state types
  */
sealed trait StorageState extends Product with Serializable {

  /**
    * @return the schema reference that storages conforms to
    */
  final def schema: ResourceRef = Latest(schemas.storage)

  /**
    * Converts the state into a resource representation.
    */
  def toResource(mappings: ApiMappings, base: ProjectBase): Option[StorageResource]

  /**
    * @return the current state revision
    */
  def rev: Long

  /**
    * @return the state deprecation status
    */
  def deprecated: Boolean

}

object StorageState {

  /**
    * Initial storage state.
    */
  final case object Initial extends StorageState {
    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[StorageResource] = None

    override def rev: Long = 0L

    override def deprecated: Boolean = false
  }

  /**
    * State for an existing storage
    *
    * @param id                the id of the storage
    * @param project           the project it belongs to
    * @param value             additional fields to configure the storage
    * @param source            the representation of the storage as posted by the subject
    * @param tags              the collection of tag aliases
    * @param rev               the current state revision
    * @param deprecated        the current state deprecation status
    * @param createdAt         the instant when the resource was created
    * @param createdBy         the subject that created the resource
    * @param updatedAt         the instant when the resource was last updated
    * @param updatedBy         the subject that last updated the resource
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      value: StorageValue,
      source: Secret[Json],
      tags: Map[TagLabel, Long],
      rev: Long,
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends StorageState {

    def storage: Storage =
      value match {
        case value: DiskStorageValue       => DiskStorage(id, project, value, tags, source)
        case value: S3StorageValue         => S3Storage(id, project, value, tags, source)
        case value: RemoteDiskStorageValue => RemoteDiskStorage(id, project, value, tags, source)
      }

    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[StorageResource] =
      Some(
        ResourceF(
          id = id,
          uris = ResourceUris("storages", project, id)(mappings, base),
          rev = rev,
          types = value.tpe.types,
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema,
          value = storage
        )
      )
  }

  implicit val revisionLens: Lens[StorageState, Long] = (s: StorageState) => s.rev

}
