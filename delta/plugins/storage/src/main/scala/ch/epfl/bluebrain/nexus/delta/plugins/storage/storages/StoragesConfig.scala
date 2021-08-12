package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import akka.http.scaladsl.model.Uri
import cats.implicits.toBifunctorOps
import ch.epfl.bluebrain.nexus.delta.kernel.{CacheIndexingConfig, Secret}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm, StorageType}
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{AggregateConfig, SaveProgressConfig}
import pureconfig.ConvertHelpers.{catchReadError, optF}
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure, FailureReason}
import pureconfig.generic.auto._
import pureconfig.{ConfigConvert, ConfigReader}

import scala.annotation.nowarn

/**
  * Configuration for the Storages module.
  *
  * @param aggregate             configuration of the underlying aggregate
  * @param keyValueStore         configuration of the underlying key/value store
  * @param pagination            configuration for how pagination should behave in listing operations
  * @param cacheIndexing         configuration of the cache indexing process
  * @param persistProgressConfig configuration for the persistence of progress of projections
  * @param storageTypeConfig     configuration of each of the storage types
  */
final case class StoragesConfig(
    aggregate: AggregateConfig,
    keyValueStore: KeyValueStoreConfig,
    pagination: PaginationConfig,
    cacheIndexing: CacheIndexingConfig,
    persistProgressConfig: SaveProgressConfig,
    storageTypeConfig: StorageTypeConfig
)

@nowarn("cat=unused")
object StoragesConfig {

  implicit val storageConfigReader: ConfigReader[StoragesConfig] =
    ConfigReader.fromCursor { cursor =>
      for {
        obj                   <- cursor.asObjectCursor
        aggregateCursor       <- obj.atKey("aggregate")
        aggregate             <- ConfigReader[AggregateConfig].from(aggregateCursor)
        kvStoreCursor         <- obj.atKey("key-value-store")
        kvStore               <- ConfigReader[KeyValueStoreConfig].from(kvStoreCursor)
        paginationCursor      <- obj.atKey("pagination")
        pagination            <- ConfigReader[PaginationConfig].from(paginationCursor)
        indexingCursor        <- obj.atKey("cache-indexing")
        indexing              <- ConfigReader[CacheIndexingConfig].from(indexingCursor)
        persistProgressCursor <- obj.atKey("persist-progress-config")
        persistProgress       <- ConfigReader[SaveProgressConfig].from(persistProgressCursor)
        storageType           <- ConfigReader[StorageTypeConfig].from(cursor)
      } yield StoragesConfig(aggregate, kvStore, pagination, indexing, persistProgress, storageType)
    }

  /**
    * The configuration of each of the storage types
    *
    * @param encryption configuration for storages derived from a password and its salt
    * @param disk       configuration for the disk storage
    * @param amazon     configuration for the s3 compatible storage
    * @param remoteDisk configuration for the remote disk storage
    */
  final case class StorageTypeConfig(
      disk: DiskStorageConfig,
      amazon: Option[S3StorageConfig],
      remoteDisk: Option[RemoteDiskStorageConfig]
  ) {
    def get(tpe: StorageType): Option[StorageTypeEntryConfig] =
      tpe match {
        case StorageType.DiskStorage       => Some(disk)
        case StorageType.S3Storage         => amazon
        case StorageType.RemoteDiskStorage => remoteDisk
      }
  }

  object StorageTypeConfig {
    final case class WrongAllowedKeys(defaultVolume: AbsolutePath) extends FailureReason {
      val description: String = s"'allowed-volumes' must contain at least '$defaultVolume' (default-volume)"
    }

    implicit val storageTypeConfigReader: ConfigReader[StorageTypeConfig] = ConfigReader.fromCursor { cursor =>
      for {
        obj        <- cursor.asObjectCursor
        diskCursor <- obj.atKey("disk")
        disk       <- ConfigReader[DiskStorageConfig].from(diskCursor)
        _          <-
          Option
            .when(disk.allowedVolumes.contains(disk.defaultVolume))(())
            .toRight(
              ConfigReaderFailures(ConvertFailure(WrongAllowedKeys(disk.defaultVolume), None, "disk.allowed-volumes"))
            )

        amazonCursor        <- obj.atKeyOrUndefined("amazon").asObjectCursor
        amazonEnabledCursor <- amazonCursor.atKey("enabled")
        amazonEnabled       <- amazonEnabledCursor.asBoolean
        amazon              <- ConfigReader[S3StorageConfig].from(amazonCursor)
        remoteCursor        <- obj.atKeyOrUndefined("remote-disk").asObjectCursor
        remoteEnabledCursor <- remoteCursor.atKey("enabled")
        remoteEnabled       <- remoteEnabledCursor.asBoolean
        remote              <- ConfigReader[RemoteDiskStorageConfig].from(remoteCursor)
      } yield StorageTypeConfig(
        disk,
        Option.when(amazonEnabled)(amazon),
        Option.when(remoteEnabled)(remote)
      )
    }

  }

  /**
    * Common parameters on different storages configuration
    */
  sealed trait StorageTypeEntryConfig {
    def defaultReadPermission: Permission
    def defaultWritePermission: Permission
    def showLocation: Boolean
    def defaultMaxFileSize: Long
  }

  /**
    * Disk storage configuration
    *
    * @param defaultVolume          the base [[Path]] where the files are stored
    * @param allowedVolumes         the allowed set of [[Path]]s where the files are stored
    * @param digestAlgorithm        algorithm for checksum calculation
    * @param defaultReadPermission  the default permission required in order to download a file from a disk storage
    * @param defaultWritePermission the default permission required in order to upload a file to a disk storage
    * @param showLocation           flag to decide whether or not to show the absolute location of the files in the metadata response
    * @param defaultCapacity        the default capacity available to store the files
    * @param defaultMaxFileSize     the default maximum allowed file size (in bytes) for uploaded files
    */
  final case class DiskStorageConfig(
      defaultVolume: AbsolutePath,
      allowedVolumes: Set[AbsolutePath],
      digestAlgorithm: DigestAlgorithm,
      defaultReadPermission: Permission,
      defaultWritePermission: Permission,
      showLocation: Boolean,
      defaultCapacity: Option[Long],
      defaultMaxFileSize: Long
  ) extends StorageTypeEntryConfig

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
      defaultAccessKey: Option[Secret[String]],
      defaultSecretKey: Option[Secret[String]],
      defaultReadPermission: Permission,
      defaultWritePermission: Permission,
      showLocation: Boolean,
      defaultMaxFileSize: Long
  ) extends StorageTypeEntryConfig

  /**
    * Remote Disk storage configuration
    *
    * @param defaultEndpoint        the default endpoint of the current storage
    * @param defaultCredentials     the default credentials for the defaul endpoint of the current storage
    * @param defaultReadPermission  the default permission required in order to download a file from a remote disk storage
    * @param defaultWritePermission the default permission required in order to upload a file to a remote disk storage
    * @param showLocation           flag to decide whether or not to show the absolute location of the files in the metadata response
    * @param defaultMaxFileSize     the default maximum allowed file size (in bytes) for uploaded files
    * @param client                 configuration of the remote disk client
    */
  final case class RemoteDiskStorageConfig(
      digestAlgorithm: DigestAlgorithm,
      defaultEndpoint: BaseUri,
      defaultCredentials: Option[Secret[String]],
      defaultReadPermission: Permission,
      defaultWritePermission: Permission,
      showLocation: Boolean,
      defaultMaxFileSize: Long,
      client: HttpClientConfig
  ) extends StorageTypeEntryConfig

  implicit private val uriConverter: ConfigConvert[Uri] =
    ConfigConvert.viaString[Uri](catchReadError(Uri(_)), _.toString)

  implicit private val permissionConverter: ConfigConvert[Permission] =
    ConfigConvert.viaString[Permission](optF(Permission(_).toOption), _.toString)

  implicit private val digestAlgConverter: ConfigConvert[DigestAlgorithm] =
    ConfigConvert.viaString[DigestAlgorithm](optF(DigestAlgorithm(_)), _.toString)

  implicit private val pathConverter: ConfigConvert[AbsolutePath] =
    ConfigConvert.viaString[AbsolutePath](
      str => AbsolutePath(str).leftMap(err => CannotConvert(str, "AbsolutePath", err)),
      _.toString
    )
}
