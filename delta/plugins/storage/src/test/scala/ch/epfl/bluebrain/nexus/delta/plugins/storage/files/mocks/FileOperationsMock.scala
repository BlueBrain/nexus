package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.mocks

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.{DiskUploadingFile, S3UploadingFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{S3FileOperations, S3LocationGenerator}
import ch.epfl.bluebrain.nexus.delta.sdk.FileData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import org.http4s.Uri
import org.http4s.Uri.Path

object FileOperationsMock {

  def forDisk(implicit uuidf: UUIDF): FileOperations =
    FileOperations.apply(DiskFileOperations.mk, s3Unimplemented)

  def disabled(implicit uuidf: UUIDF): FileOperations =
    FileOperations.apply(
      DiskFileOperations.mk,
      S3FileOperations.mk(S3StorageClient.disabled, new S3LocationGenerator(Path.empty))
    )

  def diskUnimplemented: DiskFileOperations = new DiskFileOperations {
    def fetch(path: Uri.Path): FileData                             = ???
    def save(uploading: DiskUploadingFile): IO[FileStorageMetadata] = ???
  }

  def s3Unimplemented: S3FileOperations = new S3FileOperations {
    def fetch(bucket: String, path: Uri.Path): FileData                                                            = ???
    def save(uploading: S3UploadingFile): IO[FileStorageMetadata]                                                  = ???
    def link(bucket: String, path: Uri.Path): IO[S3FileOperations.S3FileMetadata]                                  = ???
    def delegate(bucket: String, project: ProjectRef, filename: String): IO[S3FileOperations.S3DelegationMetadata] = ???
  }
}
