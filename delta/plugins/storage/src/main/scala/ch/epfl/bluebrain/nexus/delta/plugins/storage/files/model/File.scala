package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.File.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.ShowFileLocation
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceShift
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Tags}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

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
    tags: Tags,
    sourceFile: Option[ResourceRef]
) {
  def metadata: Metadata = Metadata(tags.tags)
}

object File {

  final case class Metadata(tags: List[UserTag])

  implicit def fileEncoder(implicit showLocation: ShowFileLocation): Encoder.AsObject[File] =
    Encoder.encodeJsonObject.contramapObject { file =>
      implicit val storageType: StorageType = file.storageType
      val storageJson                       = Json.obj(
        keywords.id  -> file.storage.iri.asJson,
        keywords.tpe -> storageType.iri.asJson,
        "_rev"       -> file.storage.rev.asJson
      )
      val attrJson                          = file.attributes.asJsonObject
      val sourceFileJson                    = file.sourceFile.fold(JsonObject.empty)(f => JsonObject("_sourceFile" := f.asJson))
      sourceFileJson deepMerge attrJson add ("_storage", storageJson)
    }

  implicit def fileJsonLdEncoder(implicit showLocation: ShowFileLocation): JsonLdEncoder[File] =
    JsonLdEncoder.computeFromCirce(_.id, Files.context)

  implicit private val fileMetadataEncoder: Encoder[Metadata] = { m =>
    Json.obj("_tags" -> m.tags.asJson)
  }

  implicit val fileMetadataJsonLdEncoder: JsonLdEncoder[Metadata] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.metadata))

  type Shift = ResourceShift[FileState, File, Metadata]

  def shift(files: Files)(implicit baseUri: BaseUri, showLocation: ShowFileLocation): Shift =
    ResourceShift.withMetadata[FileState, File, Metadata](
      Files.entityType,
      (ref, project) => files.fetch(FileId(ref, project)),
      state => state.toResource,
      value => JsonLdContent(value, value.value.asJson, Some(value.value.metadata))
    )

}
