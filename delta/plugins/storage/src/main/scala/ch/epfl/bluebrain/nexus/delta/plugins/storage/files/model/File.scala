package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.ShowFileLocation
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceShift
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef, Tags}
import io.circe.generic.extras.Configuration
import io.circe.syntax.*
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
)

object File {

  implicit def fileEncoder(implicit showLocation: ShowFileLocation): Encoder.AsObject[File] =
    Encoder.encodeJsonObject.contramapObject { file =>
      val storageType: StorageType                      = file.storageType
      val storageJson                                   = Json.obj(
        keywords.id  -> file.storage.iri.asJson,
        keywords.tpe -> storageType.iri.asJson,
        "_rev"       -> file.storage.rev.asJson
      )
      val attrEncoder: Encoder.AsObject[FileAttributes] = FileAttributes.createConfiguredEncoder(
        Configuration.default,
        underscoreFieldsForMetadata = true,
        removePath = true,
        removeLocation = !showLocation.types.contains(storageType)
      )
      attrEncoder.encodeObject(file.attributes).add("_storage", storageJson)
    }

  implicit def fileJsonLdEncoder(implicit showLocation: ShowFileLocation): JsonLdEncoder[File] =
    JsonLdEncoder.computeFromCirce(_.id, Files.context)

  type Shift = ResourceShift[FileState, File]

  def shift(files: Files)(implicit baseUri: BaseUri, showLocation: ShowFileLocation): Shift =
    ResourceShift[FileState, File](
      Files.entityType,
      (ref, project) => files.fetch(FileId(ref, project)),
      state => state.toResource,
      value => JsonLdContent(value, value.value.asJson, value.value.tags)
    )

}
