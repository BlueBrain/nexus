package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileAttributes, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.{DiskStorage, RemoteDiskStorage, S3Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.{FetchAttributeRejection, MoveFileRejection, RegisterFileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations.S3FileMetadata
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

import java.util.UUID

trait FileOperations extends StorageAccess {
  def save(storage: Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata]

  def fetch(storage: Storage, attributes: FileAttributes): IO[AkkaSource]

  def link(storage: Storage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata]

  def register(storage: Storage, path: Uri.Path): IO[S3FileMetadata]

  def registerUpdate(storage: Storage, path: Uri.Path, entity: BodyPartEntity): IO[FileStorageMetadata]

  def fetchAttributes(storage: Storage, attributes: FileAttributes): IO[ComputedFileAttributes]
}

object FileOperations {
  def mk(
      diskFileOps: DiskFileOperations,
      remoteDiskFileOps: RemoteDiskFileOperations,
      s3FileOps: S3FileOperations
  ): FileOperations = new FileOperations {

    override def validateStorageAccess(storage: StorageValue): IO[Unit] = storage match {
      case s: DiskStorageValue       => diskFileOps.checkVolumeExists(s.volume)
      case s: S3StorageValue         => s3FileOps.checkBucketExists(s.bucket)
      case s: RemoteDiskStorageValue => remoteDiskFileOps.checkFolderExists(s.folder)
    }

    override def save(storage: Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] =
      storage match {
        case s: DiskStorage       => diskFileOps.save(s, filename, entity)
        case s: S3Storage         => s3FileOps.save(s, filename, entity)
        case s: RemoteDiskStorage => remoteDiskFileOps.save(s, filename, entity)
      }

    override def fetch(storage: Storage, attributes: FileAttributes): IO[AkkaSource] = storage match {
      case _: DiskStorage       => diskFileOps.fetch(attributes.location.path)
      case s: S3Storage         => s3FileOps.fetch(s.value.bucket, attributes.path)
      case s: RemoteDiskStorage => remoteDiskFileOps.fetch(s.value.folder, attributes.path)
    }

    override def link(storage: Storage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata] =
      storage match {
        case storage: RemoteDiskStorage => remoteDiskFileOps.link(storage, sourcePath, filename)
        case s                          => IO.raiseError(MoveFileRejection.UnsupportedOperation(s.tpe))
      }

    override def fetchAttributes(storage: Storage, attributes: FileAttributes): IO[ComputedFileAttributes] =
      storage match {
        case s: RemoteDiskStorage => remoteDiskFileOps.fetchAttributes(s.value.folder, attributes.path)
        case s                    => IO.raiseError(FetchAttributeRejection.UnsupportedOperation(s.tpe))
      }

    override def register(storage: Storage, path: Uri.Path): IO[S3FileMetadata] =
      storage match {
        case s: S3Storage => s3FileOps.register(s.value.bucket, path)
        case s            => IO.raiseError(RegisterFileRejection.UnsupportedOperation(s.tpe))
      }

    override def registerUpdate(storage: Storage, path: Uri.Path, entity: BodyPartEntity): IO[FileStorageMetadata] =
      storage match {
        case s: S3Storage => s3FileOps.registerUpdate(s, path, entity)
        case s            => IO.raiseError(RegisterFileRejection.UnsupportedOperation(s.tpe))
      }
  }

  /**
    * Builds a relative file path with intermediate folders taken from the passed ''uuid''
    *
    * Example: uuid = 12345678-90ab-cdef-abcd-1234567890ab {org}/{proj}/1/2/3/4/5/6/7/8/{filename}
    */
  def intermediateFolders(ref: ProjectRef, uuid: UUID, filename: String): String =
    s"$ref/${uuid.toString.toLowerCase.takeWhile(_ != '-').mkString("/")}/$filename"
}
