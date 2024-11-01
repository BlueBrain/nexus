package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.{DiskUploadingFile, S3UploadingFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient.RemoteDiskStorageClientDisabled
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{S3FileOperations, S3LocationGenerator}
import ch.epfl.bluebrain.nexus.delta.kernel.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

object FileOperationsMock {

  def forRemoteDisk(client: RemoteDiskStorageClient)(implicit uuidf: UUIDF): FileOperations =
    FileOperations.apply(diskUnimplemented, RemoteDiskFileOperations.mk(client), s3Unimplemented)

  def forDiskAndRemoteDisk(client: RemoteDiskStorageClient)(implicit as: ActorSystem, uuidf: UUIDF): FileOperations =
    FileOperations.apply(DiskFileOperations.mk, RemoteDiskFileOperations.mk(client), s3Unimplemented)

  def disabled(implicit as: ActorSystem, uuidf: UUIDF): FileOperations =
    FileOperations.apply(
      DiskFileOperations.mk,
      RemoteDiskFileOperations.mk(RemoteDiskStorageClientDisabled),
      S3FileOperations.mk(S3StorageClient.disabled, new S3LocationGenerator(Path.Empty))
    )

  def diskUnimplemented: DiskFileOperations = new DiskFileOperations {
    def fetch(path: Uri.Path): IO[AkkaSource]                       = ???
    def save(uploading: DiskUploadingFile): IO[FileStorageMetadata] = ???
  }

  def s3Unimplemented: S3FileOperations = new S3FileOperations {
    def fetch(bucket: String, path: Uri.Path): IO[AkkaSource]                                                      = ???
    def save(uploading: S3UploadingFile): IO[FileStorageMetadata]                                                  = ???
    def link(bucket: String, path: Uri.Path): IO[S3FileOperations.S3FileMetadata]                                  = ???
    def delegate(bucket: String, project: ProjectRef, filename: String): IO[S3FileOperations.S3DelegationMetadata] = ???
  }
}
