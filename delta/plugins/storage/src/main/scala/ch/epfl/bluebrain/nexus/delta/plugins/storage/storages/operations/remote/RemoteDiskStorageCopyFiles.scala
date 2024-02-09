package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.{RemoteDiskCopyDetails, RemoteDiskCopyPaths}

trait RemoteDiskStorageCopyFiles {
  def copyFiles(
      destStorage: RemoteDiskStorage,
      copyDetails: NonEmptyList[RemoteDiskCopyDetails]
  ): IO[NonEmptyList[FileAttributes]]
}

object RemoteDiskStorageCopyFiles {

  def mk(client: RemoteDiskStorageClient): RemoteDiskStorageCopyFiles = new RemoteDiskStorageCopyFiles {
    def copyFiles(
        destStorage: RemoteDiskStorage,
        copyDetails: NonEmptyList[RemoteDiskCopyDetails]
    ): IO[NonEmptyList[FileAttributes]] = {

      val paths = remoteDiskCopyPaths(destStorage, copyDetails)

      client.copyFiles(destStorage.value.folder, paths).map { destPaths =>
        copyDetails.zip(paths).zip(destPaths).map { case ((copyDetails, remoteCopyPaths), absoluteDestPath) =>
          mkDestAttributes(copyDetails, remoteCopyPaths.destPath, absoluteDestPath)
        }
      }
    }
  }

  private def mkDestAttributes(
      cd: RemoteDiskCopyDetails,
      relativeDestPath: Path,
      absoluteDestPath: Uri
  ): FileAttributes = {
    val sourceFileMetadata         = cd.sourceMetadata
    val sourceUserSuppliedMetadata = cd.sourceUserSuppliedMetadata
    FileAttributes(
      uuid = cd.destUuid,
      location = absoluteDestPath,
      path = relativeDestPath,
      filename = sourceUserSuppliedMetadata.filename,
      mediaType = sourceUserSuppliedMetadata.mediaType,
      keywords = sourceUserSuppliedMetadata.keywords,
      description = sourceUserSuppliedMetadata.description,
      bytes = sourceFileMetadata.bytes,
      digest = sourceFileMetadata.digest,
      origin = sourceFileMetadata.origin
    )
  }

  private def remoteDiskCopyPaths(destStorage: RemoteDiskStorage, copyDetails: NonEmptyList[RemoteDiskCopyDetails]) =
    copyDetails.map { cd =>
      val destinationPath =
        Uri.Path(intermediateFolders(destStorage.project, cd.destUuid, cd.sourceUserSuppliedMetadata.filename))
      val sourcePath      = cd.sourcePath
      RemoteDiskCopyPaths(cd.sourceBucket, sourcePath, destinationPath)
    }
}
