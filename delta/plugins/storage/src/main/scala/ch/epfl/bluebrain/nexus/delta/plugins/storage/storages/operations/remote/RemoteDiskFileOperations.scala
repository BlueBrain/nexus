package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchAttributeRejection.WrappedFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.RemoteUploadingFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}

import java.util.UUID

trait RemoteDiskFileOperations {
  def checkFolderExists(folder: Label): IO[Unit]

  def legacyLink(storage: RemoteDiskStorage, sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata]

  def fetch(folder: Label, path: Uri.Path): IO[AkkaSource]

  def save(uploading: RemoteUploadingFile): IO[FileStorageMetadata]

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

      override def fetch(folder: Label, path: Uri.Path): IO[AkkaSource] = client.getFile(folder, path)

      override def save(uploading: RemoteUploadingFile): IO[FileStorageMetadata] =
        for {
          (uuid, destinationPath) <- generateRandomPath(uploading.project, uploading.filename)
          attr                    <- client.createFile(uploading.folder, destinationPath, uploading.entity)
        } yield metadataFromAttributes(attr, uuid, destinationPath, FileAttributesOrigin.Client)

      override def legacyLink(
          storage: RemoteDiskStorage,
          sourcePath: Uri.Path,
          filename: String
      ): IO[FileStorageMetadata] =
        for {
          (uuid, destinationPath) <- generateRandomPath(storage.project, filename)
          attr                    <- client.moveFile(storage.value.folder, sourcePath, destinationPath)
        } yield metadataFromAttributes(attr, uuid, destinationPath, FileAttributesOrigin.Storage)

      private def metadataFromAttributes(
          attr: RemoteDiskStorageFileAttributes,
          uuid: UUID,
          destinationPath: Uri.Path,
          origin: FileAttributesOrigin
      ) =
        FileStorageMetadata(
          uuid = uuid,
          bytes = attr.bytes,
          digest = attr.digest,
          origin = origin,
          location = attr.location,
          path = destinationPath
        )

      private def generateRandomPath(project: ProjectRef, filename: String) = uuidf().map { uuid =>
        val path = Uri.Path(intermediateFolders(project, uuid, filename))
        (uuid, path)
      }

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
