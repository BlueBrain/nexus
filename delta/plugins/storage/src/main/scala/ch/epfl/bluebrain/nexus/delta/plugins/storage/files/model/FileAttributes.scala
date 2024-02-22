package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.{ContentType, Uri}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

import java.util.UUID
import scala.annotation.nowarn

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
    mediaType: Option[ContentType],
    // TODO: Remove default after ??? migration
    keywords: Map[Label, String] = Map.empty,
    bytes: Long,
    digest: Digest,
    origin: FileAttributesOrigin
) extends LimitedFileAttributes

trait LimitedFileAttributes {
  def location: Uri
  def path: Path
  def filename: String
  def mediaType: Option[ContentType]
  def keywords: Map[Label, String]
  def bytes: Long
  def digest: Digest
  def origin: FileAttributesOrigin
}

object FileAttributes {

  def from(userSuppliedMetadata: FileDescription, metadata: FileStorageMetadata): FileAttributes = {
    FileAttributes(
      metadata.uuid,
      metadata.location,
      metadata.path,
      userSuppliedMetadata.filename,
      userSuppliedMetadata.mediaType,
      userSuppliedMetadata.keywords,
      metadata.bytes,
      metadata.digest,
      metadata.origin
    )
  }

  /**
    * Enumeration of all possible inputs that generated the file attributes
    */
  sealed trait FileAttributesOrigin

  object FileAttributesOrigin {
    final case object Client extends FileAttributesOrigin

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

  def createConfiguredEncoder(
      originalConfig: Configuration,
      underscoreFields: Boolean = false,
      removePath: Boolean = false,
      removeLocation: Boolean = false
  )(implicit @nowarn("cat=unused") digestEncoder: Encoder.AsObject[Digest]): Encoder.AsObject[FileAttributes] = {
    @nowarn("cat=unused")
    implicit val config: Configuration = underscoreFields match {
      case true  => withUnderscoreFields(originalConfig)
      case false => originalConfig
    }

    object Key {
      def unapply(key: String): Option[String] = {
        if (underscoreFields && key.startsWith("_")) Some(key.drop(1))
        else Some(key)
      }
    }

    deriveConfiguredEncoder[FileAttributes].mapJsonObject { json =>
      json.filter {
        case (Key("location"), _)     => !removeLocation
        case (Key("path"), _)         => !removePath
        case (Key("keywords"), value) => !value.isEmpty()
        case _                        => true
      }
    }
  }

  private def withUnderscoreFields(configuration: Configuration): Configuration = {
    configuration.copy(transformMemberNames = key => s"_$key")
  }
}
