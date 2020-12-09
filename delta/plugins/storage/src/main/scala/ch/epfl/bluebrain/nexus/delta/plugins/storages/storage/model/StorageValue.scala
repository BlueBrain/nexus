package ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission

import java.nio.file.Path

sealed trait StorageValue extends Product with Serializable {

  /**
    * @return the storage type
    */
  def tpe: StorageType

  /**
    * @return ''true'' if this store is the project's default, ''false'' otherwise
    */
  def default: Boolean
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
      algorithm: DigestAlgorithm,
      endpoint: Uri,
      credentials: Option[AuthToken],
      folder: Label,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {
    override val tpe: StorageType = StorageType.RemoteDiskStorage
  }
}
