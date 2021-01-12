package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.{ContentType, Uri}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.instances._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

import java.util.UUID
import scala.annotation.nowarn

/**
  * Holds all the metadata information related to the file.
  *
  * @param uuid      the unique id that identifies this file.
  * @param location  the absolute location where the file gets stored
  * @param path      the relative path (from the storage) where the file gets stored
  * @param filename  the original filename of the file
  * @param mediaType the media type of the file
  * @param bytes     the size of the file file in bytes
  * @param digest    the digest information of the file
  */
final case class FileAttributes(
    uuid: UUID,
    location: Uri,
    path: Path,
    filename: String,
    mediaType: ContentType,
    bytes: Long,
    digest: Digest
)

object FileAttributes {

  @nowarn("cat=unused")
  implicit private val circeConfig: Configuration = Configuration.default.copy(transformMemberNames = k => s"_$k")

  implicit def fileAttributesEncoder(implicit
      storageType: StorageType,
      config: StorageTypeConfig
  ): Encoder.AsObject[FileAttributes] =
    Encoder.encodeJsonObject.contramapObject { attributes =>
      val obj = deriveConfiguredEncoder[FileAttributes].encodeObject(attributes)
      if (!config.get(storageType).exists(_.showLocation))
        obj.remove("_location")
      else
        obj
    }
}
