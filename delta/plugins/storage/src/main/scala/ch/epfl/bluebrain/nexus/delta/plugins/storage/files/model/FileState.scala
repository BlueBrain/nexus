package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{nxvFile, schemas, FileResource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.Lens
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef, ResourceUris, TagLabel}

import java.time.Instant

/**
  * Enumeration of File state types
  */
sealed trait FileState extends Product with Serializable {

  /**
    * @return the schema reference that storages conforms to
    */
  final def schema: ResourceRef = Latest(schemas.files)

  /**
    * @return the collection of known types of file resources
    */
  final def types: Set[Iri] = Set(nxvFile)

  /**
    * Converts the state into a resource representation.
    */
  def toResource(mappings: ApiMappings, base: ProjectBase): Option[FileResource]

  /**
    * @return the current state revision
    */
  def rev: Long

  /**
    * @return the state deprecation status
    */
  def deprecated: Boolean

}

object FileState {

  /**
    * Initial file state.
    */
  final case object Initial extends FileState {
    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[FileResource] = None

    override def rev: Long = 0L

    override def deprecated: Boolean = false
  }

  /**
    * State for an existing file
    *
    * @param id          the id of the file
    * @param project     the project it belongs to
    * @param storage     the reference to the used storage
    * @param storageType the type of storage
    * @param attributes  the file attributes
    * @param tags        the collection of tag aliases
    * @param rev         the current state revision
    * @param deprecated  the current state deprecation status
    * @param createdAt   the instant when the resource was created
    * @param createdBy   the subject that created the resource
    * @param updatedAt   the instant when the resource was last updated
    * @param updatedBy   the subject that last updated the resource
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      storage: ResourceRef.Revision,
      storageType: StorageType,
      attributes: FileAttributes,
      tags: Map[TagLabel, Long],
      rev: Long,
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends FileState {

    def file: File = File(id, project, storage, storageType, attributes, tags)

    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[FileResource] =
      Some(
        ResourceF(
          id = id,
          uris = ResourceUris("files", project, id)(mappings, base),
          rev = rev,
          types = types,
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema,
          value = file
        )
      )
  }

  implicit val revisionLens: Lens[FileState, Long] = (s: FileState) => s.rev

}
