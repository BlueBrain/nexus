package ch.epfl.bluebrain.nexus.ship.storages

import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.StorageScopeInitialization
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.{DiskStorage, RemoteDiskStorage, S3Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields.S3StorageFields
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.{AkkaSource, Defaults}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.config.InputConfig
import fs2.aws.s3.models.Models.BucketName

object StorageWiring {

  def storages(
      fetchContext: FetchContext,
      contextResolution: ResolverContextResolution,
      config: InputConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit api: JsonLdApi): IO[Storages] = {
    val noopAccess   = new StorageAccess {
      override def validateStorageAccess(storage: StorageValue): IO[Unit] = IO.unit
    }
    val amazonConfig = IO.fromOption(config.storages.storageTypeConfig.amazon)(
      new IllegalArgumentException("Amazon storage type config not found")
    )
    Storages(
      fetchContext,
      contextResolution,
      amazonConfig.map(cfg => Set(cfg.defaultWritePermission, cfg.defaultReadPermission)),
      noopAccess,
      xas,
      config.storages,
      config.serviceAccount.value,
      clock
    )(api, UUIDF.random)
  }

  def s3StorageInitializer(
      storages: Storages,
      config: InputConfig
  ): IO[StorageScopeInitialization] =
    IO.fromOption(config.storages.storageTypeConfig.amazon)(
      new IllegalArgumentException("Amazon S3 configuration is missing")
    ).map { amzConfig =>
      StorageScopeInitialization.s3(
        storages,
        config.serviceAccount.value,
        defaultS3Fields(config.targetBucket, amzConfig)
      )
    }

  private def defaultS3Fields(defaultBucket: BucketName, config: S3StorageConfig) = {
    val defaults = Defaults(
      "S3 storage",
      "Default S3 storage of the Nexus service"
    )

    S3StorageFields(
      name = Some(defaults.name),
      description = Some(defaults.description),
      default = true,
      bucket = Some(defaultBucket.value.value),
      readPermission = Some(config.defaultReadPermission),
      writePermission = Some(config.defaultWritePermission),
      maxFileSize = Some(config.defaultMaxFileSize)
    )
  }

  def failingDiskFileOperations: DiskFileOperations = new DiskFileOperations {
    override def checkVolumeExists(path: AbsolutePath): IO[Unit] =
      IO.raiseError(new IllegalArgumentException("DiskFileOperations should not be called"))

    override def fetch(path: Uri.Path): IO[AkkaSource] =
      IO.raiseError(new IllegalArgumentException("DiskFileOperations should not be called"))

    override def save(storage: DiskStorage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] =
      IO.raiseError(new IllegalArgumentException("DiskFileOperations should not be called"))
  }

  def failingRemoteDiskFileOperations: RemoteDiskFileOperations = new RemoteDiskFileOperations {
    override def checkFolderExists(folder: Label): IO[Unit] =
      IO.raiseError(new IllegalArgumentException("RemoteDiskFileOperations should not be called"))

    override def fetch(folder: Label, path: Uri.Path): IO[AkkaSource] =
      IO.raiseError(new IllegalArgumentException("RemoteDiskFileOperations should not be called"))

    override def save(storage: RemoteDiskStorage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] =
      IO.raiseError(new IllegalArgumentException("RemoteDiskFileOperations should not be called"))

    override def link(storage: RemoteDiskStorage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata] =
      IO.raiseError(new IllegalArgumentException("RemoteDiskFileOperations should not be called"))

    override def fetchAttributes(folder: Label, path: Uri.Path): IO[ComputedFileAttributes] =
      IO.raiseError(new IllegalArgumentException("RemoteDiskFileOperations should not be called"))
  }

  def registerS3FileOperationOnly(s3Client: S3StorageClient): S3FileOperations = new S3FileOperations {
    override def checkBucketExists(bucket: String): IO[Unit] =
      IO.raiseError(new IllegalArgumentException("S3FileOperations should not be called"))

    override def fetch(bucket: String, path: Uri.Path): IO[AkkaSource] =
      IO.raiseError(new IllegalArgumentException("S3FileOperations should not be called"))

    override def save(storage: S3Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] =
      IO.raiseError(new IllegalArgumentException("S3FileOperations should not be called"))

    override def register(bucket: String, path: Uri.Path): IO[S3FileOperations.S3FileMetadata] =
      S3FileOperations.registerInternal(s3Client, bucket, path)(UUIDF.random)
  }

}
