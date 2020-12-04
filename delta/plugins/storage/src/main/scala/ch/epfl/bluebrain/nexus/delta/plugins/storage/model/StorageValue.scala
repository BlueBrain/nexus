package ch.epfl.bluebrain.nexus.delta.plugins.storage.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission

import java.nio.file.Path

sealed trait StorageValue extends Product with Serializable {

  /**
    * @return the storage type
    */
  def tpe: StorageType

}

object StorageValue {

  /**
    * Necessary values to create/update a disk storage
    *
    * @param default         ''true'' if this store is the project's default backend, ''false'' otherwise
    * @param algorithm       the digest algorithm
    * @param volume          the volume this storage is going to use to save files
    * @param readPermission  the permission required in order to download a file from this storage
    * @param writePermission the permission required in order to upload a file to this storage
    * @param maxFileSize     the maximum allowed file size (in bytes) for uploaded files
    */
  final case class DiskStorageValue(
      default: Boolean,
      algorithm: Algorithm,
      volume: Path,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {
    override val tpe: StorageType = StorageType.DiskStorage
  }

  /**
    * Necessary values to create/update a S3 compatible storage
    *
    * @param default         ''true'' if this store is the project's default backend, ''false'' otherwise
    * @param algorithm       the digest algorithm
    * @param bucket          the S3 compatible bucket
    * @param settings        the S3 connection settings
    * @param readPermission  the permission required in order to download a file from this storage
    * @param writePermission the permission required in order to upload a file to this storage
    * @param maxFileSize     the maximum allowed file size (in bytes) for uploaded files
    */
  final case class S3StorageValue(
      default: Boolean,
      algorithm: Algorithm,
      bucket: String,
      settings: S3Settings,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {
    override val tpe: StorageType = StorageType.S3Storage
  }

  /**
    * Necessary values to create/update a Remote disk storage
    *
    * @param default         ''true'' if this store is the project's default backend, ''false'' otherwise
    * @param algorithm       the digest algorithm, e.g. "SHA-256"
    * @param endpoint        the endpoint for the remote storage
    * @param credentials     the optional credentials to access the remote storage service
    * @param folder          the rootFolder for this storage
    * @param readPermission  the permission required in order to download a file from this storage
    * @param writePermission the permission required in order to upload a file to this storage
    * @param maxFileSize     the maximum allowed file size (in bytes) for uploaded files
    */
  final case class RemoteDiskStorageValue(
      default: Boolean,
      algorithm: Algorithm,
      endpoint: Uri,
      credentials: Option[String],
      folder: String,
      readPermission: Permission,
      writePermission: Permission,
      maxFileSize: Long
  ) extends StorageValue {
    override val tpe: StorageType = StorageType.RemoteDiskStorage
  }
}
