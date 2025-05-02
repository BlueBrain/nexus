package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Decoder, Encoder}
import org.http4s.Uri
import org.http4s.Uri.Path

import java.util.UUID

/**
  * Holds all the metadata information related to the file.
  *
  * @param uuid
  *   the unique id that identifies this file.
  * @param location
  *   the absolute location where the file gets stored
  * @param path
  *   the relative path (from the storage) where the file gets stored
  * @param filename
  *   the original filename of the file
  * @param mediaType
  *   the optional media type of the file
  * @param bytes
  *   the size of the file in bytes
  * @param digest
  *   the digest information of the file
  * @param origin
  *   the type of input that generated the current file attributes
  */
final case class FileAttributes(
    uuid: UUID,
    location: Uri,
    path: Path,
    filename: String,
    mediaType: Option[MediaType],
    // TODO: Remove default after ??? migration
    keywords: Map[Label, String] = Map.empty,
    description: Option[String] = None,
    name: Option[String],
    bytes: Long,
    digest: Digest,
    origin: FileAttributesOrigin
)

object FileAttributes {

  def from(
      filename: String,
      mediaType: Option[MediaType],
      metadata: Option[FileCustomMetadata],
      storageMetadata: FileStorageMetadata
  ): FileAttributes = {
    val customMetadata = metadata.getOrElse(FileCustomMetadata.empty)
    FileAttributes(
      storageMetadata.uuid,
      storageMetadata.location,
      storageMetadata.path,
      filename,
      mediaType,
      customMetadata.keywords.getOrElse(Map.empty),
      customMetadata.description,
      customMetadata.name,
      storageMetadata.bytes,
      storageMetadata.digest,
      storageMetadata.origin
    )
  }

  /** Set the metadata of the provided [[FileAttributes]] to the metadata provided in [[FileCustomMetadata]] */
  def setCustomMetadata(attr: FileAttributes, newCustomMetadata: FileCustomMetadata): FileAttributes =
    attr.copy(
      keywords = newCustomMetadata.keywords.getOrElse(Map.empty),
      description = newCustomMetadata.description,
      name = newCustomMetadata.name
    )

  /**
    * Enumeration of all possible inputs that generated the file attributes
    */
  sealed trait FileAttributesOrigin

  object FileAttributesOrigin {
    final case object Client extends FileAttributesOrigin

    final case object Storage extends FileAttributesOrigin
    final case object Link    extends FileAttributesOrigin

    implicit val fileAttributesEncoder: Encoder[FileAttributesOrigin] = Encoder.encodeString.contramap {
      case Client  => "Client"
      case Storage => "Storage"
      case Link    => "Link"
    }

    implicit val fileAttributesDecoder: Decoder[FileAttributesOrigin] = Decoder.decodeString.emap {
      case "Client"   => Right(Client)
      case "Storage"  => Right(Storage)
      case "External" => Right(Link)
      case "Link"     => Right(Link)
      case str        => Left(s"'$str' is not a 'FileAttributesOrigin'")
    }
  }

  def createConfiguredEncoder(
      originalConfig: Configuration,
      underscoreFieldsForMetadata: Boolean = false,
      removePath: Boolean = false,
      removeLocation: Boolean = false
  )(implicit digestEncoder: Encoder.AsObject[Digest]): Encoder.AsObject[FileAttributes] = {

    implicit val config: Configuration = underscoreFieldsForMetadata match {
      case true  => withUnderscoreMetadataFields(originalConfig)
      case false => originalConfig
    }

    object Key {
      def unapply(key: String): Option[String] = {
        if (underscoreFieldsForMetadata && key.startsWith("_")) Some(key.drop(1))
        else Some(key)
      }
    }

    deriveConfiguredEncoder[FileAttributes].mapJsonObject { json =>
      json.filter {
        case (Key("location"), _)        => !removeLocation
        case (Key("path"), _)            => !removePath
        case (Key("keywords"), value)    => !value.isEmpty()
        case (Key("description"), value) => !value.isNull
        case (Key("name"), value)        => !value.isNull
        case _                           => true
      }
    }
  }

  object NonMetadataKey {
    private val keys                         = Set("description", "name")
    def unapply(key: String): Option[String] = {
      Option.when(keys.contains(key))(key)
    }
  }

  private def withUnderscoreMetadataFields(configuration: Configuration): Configuration = {
    configuration.copy(transformMemberNames = {
      case NonMetadataKey(key) => key
      case metadataKey         => s"_$metadataKey"
    })
  }
}
