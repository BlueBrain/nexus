package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{nxvFile, schemas, FileResource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.instances._
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant
import scala.annotation.nowarn

/**
  * State for an existing file
  *
  * @param id
  *   the id of the file
  * @param project
  *   the project it belongs to
  * @param storage
  *   the reference to the used storage
  * @param storageType
  *   the type of storage
  * @param attributes
  *   the file attributes
  * @param tags
  *   the collection of tag aliases
  * @param rev
  *   the current state revision
  * @param deprecated
  *   the current state deprecation status
  * @param createdAt
  *   the instant when the resource was created
  * @param createdBy
  *   the subject that created the resource
  * @param updatedAt
  *   the instant when the resource was last updated
  * @param updatedBy
  *   the subject that last updated the resource
  */
final case class FileState(
    id: Iri,
    project: ProjectRef,
    storage: ResourceRef.Revision,
    storageType: StorageType,
    attributes: FileAttributes,
    tags: Tags,
    rev: Int,
    deprecated: Boolean,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends ScopedState {

  /**
    * @return
    *   the schema reference that storages conforms to
    */
  def schema: ResourceRef = Latest(schemas.files)

  /**
    * @return
    *   the collection of known types of file resources
    */
  def types: Set[Iri] = Set(nxvFile)

  private def file: File = File(id, project, storage, storageType, attributes, tags)

  def toResource(mappings: ApiMappings, base: ProjectBase): FileResource =
    ResourceF(
      id = id,
      uris = ResourceUris("files", project, id)(mappings, base),
      rev = rev.toLong,
      types = types,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = file
    )
}

object FileState {

  @nowarn("cat=unused")
  val serializer: Serializer[Iri, FileState] = {
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.CompactedJsonLd.Database._
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd.Database._
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration                        = Serializer.circeConfiguration
    implicit val digestCodec: Codec.AsObject[Digest]                 =
      deriveConfiguredCodec[Digest]
    implicit val fileAttributesCodec: Codec.AsObject[FileAttributes] =
      deriveConfiguredCodec[FileAttributes]
    implicit val codec: Codec.AsObject[FileState]                    = deriveConfiguredCodec[FileState]
    Serializer(_.id)
  }
}
