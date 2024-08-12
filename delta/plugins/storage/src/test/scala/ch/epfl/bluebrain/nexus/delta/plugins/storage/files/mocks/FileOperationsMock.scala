package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.UploadedFileInformation
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileAttributes, FileDelegationRequest, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, Storage, StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.{DiskUploadingFile, S3UploadingFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient.RemoteDiskStorageClientDisabled
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{S3FileOperations, S3LocationGenerator}
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

object FileOperationsMock {

  def forRemoteDisk(client: RemoteDiskStorageClient)(implicit uuidf: UUIDF): FileOperations =
    FileOperations.mk(diskUnimplemented, RemoteDiskFileOperations.mk(client), s3Unimplemented)

  def forDiskAndRemoteDisk(client: RemoteDiskStorageClient)(implicit as: ActorSystem, uuidf: UUIDF): FileOperations =
    FileOperations.mk(DiskFileOperations.mk, RemoteDiskFileOperations.mk(client), s3Unimplemented)

  def disabled(implicit as: ActorSystem, uuidf: UUIDF): FileOperations =
    FileOperations.mk(
      DiskFileOperations.mk,
      RemoteDiskFileOperations.mk(RemoteDiskStorageClientDisabled),
      S3FileOperations.mk(S3StorageClient.disabled, new S3LocationGenerator(Path.Empty))
    )

  def unimplemented: FileOperations = new FileOperations {
    def validateStorageAccess(storage: StorageValue): IO[Unit]                                                      = ???
    def save(storage: Storage, info: UploadedFileInformation, contentLength: Option[Long]): IO[FileStorageMetadata] =
      ???
    def link(storage: Storage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata]                     = ???
    def fetch(storage: Storage, attributes: FileAttributes): IO[AkkaSource]                                         = ???
    def fetchAttributes(storage: Storage, attributes: FileAttributes): IO[ComputedFileAttributes]                   = ???
    def link(storage: Storage, path: Uri.Path): IO[S3FileOperations.S3FileMetadata]                                 = ???
    def delegate(storage: Storage, filename: String): IO[FileDelegationRequest.TargetLocation]                      = ???
  }

  def diskUnimplemented: DiskFileOperations = new DiskFileOperations {
    def checkVolumeExists(path: AbsolutePath): IO[Unit]             = ???
    def fetch(path: Uri.Path): IO[AkkaSource]                       = ???
    def save(uploading: DiskUploadingFile): IO[FileStorageMetadata] = ???
  }

  def s3Unimplemented: S3FileOperations = new S3FileOperations {
    def checkBucketExists(bucket: String): IO[Unit]                                                                = ???
    def fetch(bucket: String, path: Uri.Path): IO[AkkaSource]                                                      = ???
    def save(uploading: S3UploadingFile): IO[FileStorageMetadata]                                                  = ???
    def link(bucket: String, path: Uri.Path): IO[S3FileOperations.S3FileMetadata]                                  = ???
    def delegate(bucket: String, project: ProjectRef, filename: String): IO[S3FileOperations.S3DelegationMetadata] = ???
  }
}
