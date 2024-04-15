package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.{DiskStorage, RemoteDiskStorage, S3Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageFetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3StorageFetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

trait FetchFile {
  def apply(storage: Storage, attributes: FileAttributes): IO[AkkaSource]
}

object FetchFile {
  def apply(remoteClient: RemoteDiskStorageClient, s3Client: S3StorageClient): FetchFile = new FetchFile {
    private val s3 = new S3StorageFetchFile(s3Client)

    override def apply(storage: Storage, attributes: FileAttributes): IO[AkkaSource] = storage match {
      case _: DiskStorage             => DiskStorageFetchFile.apply(attributes.location.path)
      case storage: S3Storage         => s3.apply(storage.value.bucket, attributes.path)
      case storage: RemoteDiskStorage => remoteClient.getFile(storage.value.folder, attributes.path)
    }
  }
}
