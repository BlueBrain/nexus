package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.{Client, Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchAttributeRejection.WrappedFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

trait RemoteDiskFileOperations {
  def checkFolderExists(folder: Label): IO[Unit]

  def link(storage: RemoteDiskStorage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata]

  def fetch(folder: Label, path: Uri.Path): IO[AkkaSource]

  def save(storage: RemoteDiskStorage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata]

  def fetchAttributes(folder: Label, path: Uri.Path): IO[ComputedFileAttributes]
}

object RemoteDiskFileOperations {

  def mk(client: RemoteDiskStorageClient)(implicit uuidf: UUIDF): RemoteDiskFileOperations =
    new RemoteDiskFileOperations {
      override def checkFolderExists(folder: Label): IO[Unit] =
        client
          .exists(folder)
          .adaptError { case err: HttpClientError =>
            StorageNotAccessible(
              err.details.fold(s"Folder '$folder' does not exist")(d => s"${err.reason}: $d")
            )
          }

      override def link(storage: RemoteDiskStorage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata] =
        for {
          uuid                                                        <- uuidf()
          destinationPath                                              = Uri.Path(intermediateFolders(storage.project, uuid, filename))
          RemoteDiskStorageFileAttributes(location, bytes, digest, _) <-
            client.moveFile(storage.value.folder, sourcePath, destinationPath)
        } yield FileStorageMetadata(
          uuid = uuid,
          bytes = bytes,
          digest = digest,
          origin = Storage,
          location = location,
          path = destinationPath
        )

      override def fetch(folder: Label, path: Uri.Path): IO[AkkaSource] = client.getFile(folder, path)

      override def save(storage: RemoteDiskStorage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] =
        for {
          uuid                                                        <- uuidf()
          path                                                         = Uri.Path(intermediateFolders(storage.project, uuid, filename))
          RemoteDiskStorageFileAttributes(location, bytes, digest, _) <-
            client.createFile(storage.value.folder, path, entity)
        } yield FileStorageMetadata(
          uuid = uuid,
          bytes = bytes,
          digest = digest,
          origin = Client,
          location = location,
          path = path
        )

      override def fetchAttributes(folder: Label, path: Uri.Path): IO[ComputedFileAttributes] =
        client
          .getAttributes(folder, path)
          .map { case RemoteDiskStorageFileAttributes(_, bytes, digest, mediaType) =>
            ComputedFileAttributes(mediaType, bytes, digest)
          }
          .adaptError { case e: FetchFileRejection =>
            WrappedFetchRejection(e)
          }
    }

}
