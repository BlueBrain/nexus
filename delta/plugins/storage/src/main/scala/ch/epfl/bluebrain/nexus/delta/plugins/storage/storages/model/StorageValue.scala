package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._

import java.nio.file.Path
import scala.annotation.nowarn

sealed trait StorageValue extends Product with Serializable {

  /**
    * @return the storage type
    */
  def tpe: StorageType

  /**
    * @return ''true'' if this store is the project's default, ''false'' otherwise
    */
  def default: Boolean

  /**
    * @return the maximum allowed file size (in bytes) for uploaded files
    */
  def maxFileSize: Long
}

object StorageValue {

  /**
    * Resolved values to create/update a disk storage
    *
    * @see [[StorageFields.DiskStorageFields]]
    */
  final case class DiskStorageValue(
      default: Boolean,
      algorithm: DigestAlgorithm,
      volume: Path,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {
    override val tpe: StorageType = StorageType.DiskStorage
  }

  /**
    * Resolved values to create/update a S3 compatible storage
    *
    * @see [[StorageFields.S3StorageFields]]
    */
  final case class S3StorageValue(
      default: Boolean,
      algorithm: DigestAlgorithm,
      bucket: String,
      endpoint: Option[Uri],
      accessKey: Option[String],
      secretKey: Option[String],
      region: Option[String],
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {
    override val tpe: StorageType = StorageType.S3Storage
  }

  /**
    * Resolved values to create/update a Remote disk storage
    *
    * @see [[StorageFields.RemoteDiskStorageFields]]
    */
  final case class RemoteDiskStorageValue(
      default: Boolean,
      endpoint: Uri,
      credentials: Option[AuthToken],
      folder: Label,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {
    override val tpe: StorageType = StorageType.RemoteDiskStorage
  }

  @nowarn("cat=unused")
  implicit private[model] val storageValueEncoder: Encoder[StorageValue] = {
    implicit val config: Configuration      = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val pathEncoder: Encoder[Path] = Encoder.encodeString.contramap(_.toString)

    Encoder.encodeJsonObject.contramapObject { storage =>
      deriveConfiguredEncoder[StorageValue].encodeObject(storage).add(keywords.tpe, storage.tpe.iri.asJson)
    }
  }
}
