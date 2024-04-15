package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileAttributes, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskFileOperations
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

trait FileOperations {
  def save(storage: Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata]

  def link(storage: Storage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata]

  def fetch(storage: Storage, attributes: FileAttributes): IO[AkkaSource]

  def fetchAttributes(storage: Storage, attributes: FileAttributes): IO[ComputedFileAttributes]
}

object FileOperations {

  // TODO do errors properly
  def mk(
      diskFileOps: DiskFileOperations,
      remoteDiskFileOps: RemoteDiskFileOperations,
      s3FileOps: S3FileOperations
  ): FileOperations = new FileOperations {

    override def save(storage: Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] =
      storage match {
        case storage: Storage.DiskStorage       => diskFileOps.save(storage, filename, entity)
        case storage: Storage.S3Storage         => s3FileOps.save(storage, filename, entity)
        case storage: Storage.RemoteDiskStorage => remoteDiskFileOps.save(storage, filename, entity)
      }

    override def link(storage: Storage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata] =
      storage match {
        case _: Storage.DiskStorage             => ???
        case _: Storage.S3Storage               => ???
        case storage: Storage.RemoteDiskStorage => remoteDiskFileOps.link(storage, sourcePath, filename)
      }

    override def fetch(storage: Storage, attributes: FileAttributes): IO[AkkaSource] = storage match {
      case _: Storage.DiskStorage                    => diskFileOps.fetch(attributes.location.path)
      case Storage.S3Storage(_, _, value, _)         => s3FileOps.fetch(value.bucket, attributes.path)
      case Storage.RemoteDiskStorage(_, _, value, _) => remoteDiskFileOps.fetch(value.folder, attributes.path)
    }

    override def fetchAttributes(storage: Storage, attributes: FileAttributes): IO[ComputedFileAttributes] =
      storage match {
        case Storage.RemoteDiskStorage(_, _, value, _) =>
          remoteDiskFileOps.fetchAttributes(value.folder, attributes.path)
        case _                                         => ???
      }
  }

}
