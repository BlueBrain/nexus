package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.File.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceShift
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegmentRef, Tags}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.syntax._
import io.circe.{Encoder, Json}

/**
  * A representation of a file information
  *
  * @param id
  *   the file identifier
  * @param project
  *   the project where the file belongs
  * @param storage
  *   the reference to the used storage
  * @param storageType
  *   the type of storage
  * @param attributes
  *   the file attributes
  * @param tags
  *   the file tags
  */
final case class File(
    id: Iri,
    project: ProjectRef,
    storage: ResourceRef.Revision,
    storageType: StorageType,
    attributes: FileAttributes,
    tags: Tags
) {
  def metadata: Metadata = Metadata(tags.tags)
}

object File {

  final case class Metadata(tags: List[UserTag])

  implicit def fileEncoder(implicit config: StorageTypeConfig): Encoder[File] = { file =>
    implicit val storageType: StorageType = file.storageType
    val storageJson                       = Json.obj(
      keywords.id  -> file.storage.iri.asJson,
      keywords.tpe -> storageType.iri.asJson,
      "_rev"       -> file.storage.rev.asJson
    )
    file.attributes.asJson.mapObject(_.add("_storage", storageJson))
  }

  implicit def fileJsonLdEncoder(implicit config: StorageTypeConfig): JsonLdEncoder[File] =
    JsonLdEncoder.computeFromCirce(_.id, Files.context)

  implicit private val fileMetadataEncoder: Encoder[Metadata] = { m =>
    Json.obj("_tags" -> m.tags.asJson)
  }

  implicit val fileMetadataJsonLdEncoder: JsonLdEncoder[Metadata] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.metadata))

  type Shift = ResourceShift[FileState, File, Metadata]

  def shift(files: Files)(implicit baseUri: BaseUri, config: StorageTypeConfig): Shift =
    ResourceShift.withMetadata[FileState, File, Metadata](
      Files.entityType,
      (ref, project) => files.fetch(IdSegmentRef(ref), project),
      state => state.toResource,
      value => JsonLdContent(value, value.value.asJson, Some(value.value.metadata))
    )

}
