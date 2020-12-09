package ch.epfl.bluebrain.nexus.delta.plugins.storages.storage

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.storages.config.{AggregateConfig, IndexingConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig

import java.nio.file.Path

/**
  * Configuration for the Storages module.
  *
  * @param aggregate         configuration of the underlying aggregate
  * @param keyValueStore     configuration of the underlying key/value store
  * @param pagination        configuration for how pagination should behave in listing operations
  * @param indexing          configuration of the indexing process
  * @param storageTypeConfig configuration of each of the storage types
  */
final case class StoragesConfig(
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    indexing: IndexingConfig,
    storageTypeConfig: StorageTypeConfig
)

object StoragesConfig {

  /**
    * The configuration of each of the storage types
    * @param disk          configuration for the disk storage
    * @param amazon        configuration for the s3 compatible storage
    * @param remoteDisk    configuration for the remote disk storage
    */
  @SuppressWarnings(Array("OptionGet"))
  final case class StorageTypeConfig(
      disk: DiskStorageConfig,
      amazon: Option[S3StorageConfig],
      remoteDisk: Option[RemoteDiskStorageConfig]
  ) {
    lazy val amazonUnsafe: S3StorageConfig             = amazon.get
    lazy val remoteDiskUnsafe: RemoteDiskStorageConfig = remoteDisk.get
  }

  /**
    * Disk storage configuration
    *
    * @param volume          the base [[Path]] where the files are stored
    * @param digestAlgorithm algorithm for checksum calculation
    * @param readPermission  the default permission required in order to download a file from a disk storage
    * @param writePermission the default permission required in order to upload a file to a disk storage
    * @param showLocation    flag to decide whether or not to show the absolute location of the files in the metadata response
    * @param maxFileSize     the default maximum allowed file size (in bytes) for uploaded files
    */
  final case class DiskStorageConfig(
      volume: Path,
      digestAlgorithm: DigestAlgorithm,
      readPermission: Permission,
      writePermission: Permission,
      showLocation: Boolean,
      maxFileSize: Long
  )

  /**
    * Amazon S3 compatible storage configuration
    *
    * @param digestAlgorithm algorithm for checksum calculation
    * @param defaultEndpoint the default endpoint of the current storage
    * @param accessKey       the access key for the default endpoint, when provided
    * @param secertKey       the secret key for the default endpoint, when provided
    * @param readPermission  the default permission required in order to download a file from a s3 storage
    * @param writePermission the default permission required in order to upload a file to a s3 storage
    * @param showLocation    flag to decide whether or not to show the absolute location of the files in the metadata response
    * @param maxFileSize     the default maximum allowed file size (in bytes) for uploaded files
    */
  final case class S3StorageConfig(
      digestAlgorithm: DigestAlgorithm,
      defaultEndpoint: Option[Uri],
      accessKey: Option[String],
      secretKey: Option[String],
      readPermission: Permission,
      writePermission: Permission,
      showLocation: Boolean,
      maxFileSize: Long
  )

  /**
    * Remote Disk storage configuration
    *
    * @param defaultEndpoint       the default endpoint of the current storage
    * @param defaultEndpointPrefix the default endpoint prefix
    * @param defaultCredentials    the default credentials for the defaul endpoint of the current storage
    * @param digestAlgorithm       the default digest algorithm of the current storage
    * @param readPermission        the default permission required in order to download a file from a remote disk storage
    * @param writePermission       the default permission required in order to upload a file to a remote disk storage
    * @param showLocation          flag to decide whether or not to show the absolute location of the files in the metadata response
    * @param maxFileSize           the default maximum allowed file size (in bytes) for uploaded files
    */
  final case class RemoteDiskStorageConfig(
      defaultEndpoint: Uri,
      defaultEndpointPrefix: String,
      defaultCredentials: Option[AuthToken],
      digestAlgorithm: DigestAlgorithm,
      readPermission: Permission,
      writePermission: Permission,
      showLocation: Boolean,
      maxFileSize: Long
  ) {
    val endpoint: Uri = defaultEndpoint / defaultEndpointPrefix
  }
}
