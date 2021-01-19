package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.{ContentType, Uri}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.instances._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.{Decoder, Encoder}
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
  * @param origin      the type of input that generated the current file attributes
  */
final case class FileAttributes(
    uuid: UUID,
    location: Uri,
    path: Path,
    filename: String,
    mediaType: ContentType,
    bytes: Long,
    digest: Digest,
    origin: FileAttributesOrigin
)

object FileAttributes {

  /**
    * Enumeration of all possible inputs that generated the file attributes
    */
  sealed trait FileAttributesOrigin

  object FileAttributesOrigin {
    final case object Client  extends FileAttributesOrigin
    final case object Storage extends FileAttributesOrigin

    implicit val fileAttributesEncoder: Encoder[FileAttributesOrigin] = Encoder.encodeString.contramap {
      case Client  => "Client"
      case Storage => "Storage"
    }

    implicit val fileAttributesDecoder: Decoder[FileAttributesOrigin] = Decoder.decodeString.emap {
      case "Client"  => Right(Client)
      case "Storage" => Right(Storage)
      case str       => Left(s"'$str' is not a 'FileAttributesOrigin'")
    }
  }

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
