package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileAttributes, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

trait FileOperations {
  def save(storage: Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata]

  def link(storage: Storage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata]

  def fetch(storage: Storage, attributes: FileAttributes): IO[AkkaSource]

  def fetchAttributes(storage: Storage, attributes: FileAttributes): IO[ComputedFileAttributes]
}

object FileOperations {

  def mk(
      s3Client: S3StorageClient,
      remoteClient: RemoteDiskStorageClient
  ): FileOperations = new FileOperations {

    println(s3Client)
    println(remoteClient)

    override def save(storage: Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] = ???

    override def link(storage: Storage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata] = ???

    override def fetch(storage: Storage, attributes: FileAttributes): IO[AkkaSource] = ???

    override def fetchAttributes(storage: Storage, attributes: FileAttributes): IO[ComputedFileAttributes] = ???
  }

}
