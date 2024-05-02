package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileAttributes, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, Storage, StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient.RemoteDiskStorageClientDisabled
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

object FileOperationsMock {

  def forRemoteDisk(client: RemoteDiskStorageClient)(implicit uuidf: UUIDF): FileOperations =
    FileOperations.mk(diskUnimplemented, RemoteDiskFileOperations.mk(client), s3Unimplemented)

  def forDiskAndRemoteDisk(client: RemoteDiskStorageClient)(implicit as: ActorSystem, uuidf: UUIDF): FileOperations =
    FileOperations.mk(DiskFileOperations.mk, RemoteDiskFileOperations.mk(client), s3Unimplemented)

  def disabled(implicit as: ActorSystem, uuidf: UUIDF): FileOperations =
    FileOperations.mk(
      DiskFileOperations.mk,
      RemoteDiskFileOperations.mk(RemoteDiskStorageClientDisabled),
      S3FileOperations.mk(S3StorageClient.disabled)
    )

  def unimplemented: FileOperations = new FileOperations {
    def validateStorageAccess(storage: StorageValue): IO[Unit]                                    = ???
    def save(storage: Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] = ???
    def link(storage: Storage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata]   = ???
    def fetch(storage: Storage, attributes: FileAttributes): IO[AkkaSource]                       = ???
    def fetchAttributes(storage: Storage, attributes: FileAttributes): IO[ComputedFileAttributes] = ???
    def register(storage: Storage, path: Uri.Path): IO[S3FileOperations.S3FileMetadata]           = ???
  }

  def diskUnimplemented: DiskFileOperations = new DiskFileOperations {
    def checkVolumeExists(path: AbsolutePath): IO[Unit]                                                       = ???
    def fetch(path: Uri.Path): IO[AkkaSource]                                                                 = ???
    def save(storage: Storage.DiskStorage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] = ???
  }

  def s3Unimplemented: S3FileOperations = new S3FileOperations {
    def checkBucketExists(bucket: String): IO[Unit]                                                         = ???
    def fetch(bucket: String, path: Uri.Path): IO[AkkaSource]                                               = ???
    def save(storage: Storage.S3Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] = ???
    def register(bucket: String, path: Uri.Path): IO[S3FileOperations.S3FileMetadata]                       = ???
  }
}
