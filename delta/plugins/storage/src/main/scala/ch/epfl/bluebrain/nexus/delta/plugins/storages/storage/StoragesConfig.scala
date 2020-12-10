package ch.epfl.bluebrain.nexus.delta.plugins.storages.storage

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storages.config.{AggregateConfig, IndexingConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

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
  final case class StorageTypeConfig(
      disk: DiskStorageConfig,
      amazon: Option[S3StorageConfig],
      remoteDisk: Option[RemoteDiskStorageConfig]
  )

  /**
    * Disk storage configuration
    *
    * @param defaultVolume          the base [[Path]] where the files are stored
    * @param digestAlgorithm        algorithm for checksum calculation
    * @param defaultReadPermission  the default permission required in order to download a file from a disk storage
    * @param defaultWritePermission the default permission required in order to upload a file to a disk storage
    * @param showLocation           flag to decide whether or not to show the absolute location of the files in the metadata response
    * @param defaultMaxFileSize     the default maximum allowed file size (in bytes) for uploaded files
    */
  final case class DiskStorageConfig(
      defaultVolume: Path,
      digestAlgorithm: DigestAlgorithm,
      defaultReadPermission: Permission,
      defaultWritePermission: Permission,
      showLocation: Boolean,
      defaultMaxFileSize: Long
  )

  /**
    * Amazon S3 compatible storage configuration
    *
    * @param digestAlgorithm        algorithm for checksum calculation
    * @param defaultEndpoint        the default endpoint of the current storage
    * @param defaultAccessKey       the access key for the default endpoint, when provided
    * @param defaultSecretKey       the secret key for the default endpoint, when provided
    * @param defaultReadPermission  the default permission required in order to download a file from a s3 storage
    * @param defaultWritePermission the default permission required in order to upload a file to a s3 storage
    * @param showLocation           flag to decide whether or not to show the absolute location of the files in the metadata response
    * @param defaultMaxFileSize     the default maximum allowed file size (in bytes) for uploaded files
    */
  final case class S3StorageConfig(
      digestAlgorithm: DigestAlgorithm,
      defaultEndpoint: Option[Uri],
      defaultAccessKey: Option[String],
      defaultSecretKey: Option[String],
      defaultReadPermission: Permission,
      defaultWritePermission: Permission,
      showLocation: Boolean,
      defaultMaxFileSize: Long
  )

  /**
    * Remote Disk storage configuration
    *
    * @param defaultEndpoint        the default endpoint of the current storage
    * @param defaultEndpointPrefix  the default endpoint prefix
    * @param defaultCredentials     the default credentials for the defaul endpoint of the current storage
    * @param defaultReadPermission  the default permission required in order to download a file from a remote disk storage
    * @param defaultWritePermission the default permission required in order to upload a file to a remote disk storage
    * @param showLocation           flag to decide whether or not to show the absolute location of the files in the metadata response
    * @param defaultMaxFileSize     the default maximum allowed file size (in bytes) for uploaded files
    */
  final case class RemoteDiskStorageConfig(
      defaultEndpoint: Uri,
      defaultEndpointPrefix: String,
      defaultCredentials: Option[AuthToken],
      defaultReadPermission: Permission,
      defaultWritePermission: Permission,
      showLocation: Boolean,
      defaultMaxFileSize: Long
  ) {
    val endpoint: Uri = defaultEndpoint / defaultEndpointPrefix
  }
}
