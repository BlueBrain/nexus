package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileAttributes, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.{RemoteDiskStorageLinkFile, RemoteStorageFetchAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

trait FileOperations {
  def save(storage: Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata]

  def link(storage: Storage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata]

  def fetch(storage: Storage, attributes: FileAttributes): IO[AkkaSource]

  def fetchAttributes(storage: Storage, attributes: FileAttributes): IO[ComputedFileAttributes]
}

object FileOperations {

  def mk2(
      saveFile: SaveFile,
      linkFile: RemoteDiskStorageLinkFile,
      fetchFile: FetchFile,
      fetchAttr: RemoteStorageFetchAttributes
  ): FileOperations = new FileOperations {

    override def save(storage: Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] =
      saveFile.apply(storage, filename, entity)

    override def link(storage: Storage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata] =
      storage match {
        case storage: Storage.RemoteDiskStorage => linkFile.apply(storage, sourcePath, filename)
        case other                              => unsupportedErr(other.tpe)
      }

    override def fetch(storage: Storage, attributes: FileAttributes): IO[AkkaSource] =
      fetchFile.apply(storage, attributes)

    override def fetchAttributes(storage: Storage, attributes: FileAttributes): IO[ComputedFileAttributes] =
      storage match {
        case storage: Storage.RemoteDiskStorage => fetchAttr.apply(storage, attributes.path)
        case other                              => unsupportedErr(other.tpe)
      }

    // TODO this error can be one for everything
    private def unsupportedErr(storageType: StorageType) =
      IO.raiseError(MoveFileRejection.UnsupportedOperation(storageType))
  }

  def mk(
      s3Client: S3StorageClient,
      remoteClient: RemoteDiskStorageClient
  )(implicit
      as: ActorSystem,
      uuidf: UUIDF
  ): FileOperations = mk2(
    saveFile = SaveFile.apply(remoteClient, s3Client),
    fetchFile = FetchFile.apply(remoteClient, s3Client),
    linkFile = new RemoteDiskStorageLinkFile(remoteClient),
    fetchAttr = new RemoteStorageFetchAttributes(remoteClient)
  )

}
