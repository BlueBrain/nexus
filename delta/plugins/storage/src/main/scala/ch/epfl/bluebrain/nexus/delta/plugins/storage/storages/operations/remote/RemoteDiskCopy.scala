package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskCopyPaths

trait RemoteDiskCopy {
  def copyFiles(
      destStorage: RemoteDiskStorage,
      copyDetails: NonEmptyList[RemoteDiskCopyDetails]
  ): IO[NonEmptyList[FileAttributes]]
}

object RemoteDiskCopy {

  def mk(client: RemoteDiskStorageClient): RemoteDiskCopy = new RemoteDiskCopy {
    def copyFiles(
        destStorage: RemoteDiskStorage,
        copyDetails: NonEmptyList[RemoteDiskCopyDetails]
    ): IO[NonEmptyList[FileAttributes]] = {

      val paths = remoteDiskCopyPaths(destStorage, copyDetails)

      client.copyFiles(destStorage.value.folder, paths)(destStorage.value.endpoint).map { destPaths =>
        copyDetails.zip(paths).zip(destPaths).map { case ((copyDetails, remoteCopyPaths), absoluteDestPath) =>
          mkDestAttributes(copyDetails, remoteCopyPaths.destPath, absoluteDestPath)
        }
      }
    }
  }

  private def mkDestAttributes(cd: RemoteDiskCopyDetails, relativeDestPath: Path, absoluteDestPath: Uri) = {
    val destDesc   = cd.destinationDesc
    val sourceAttr = cd.sourceAttributes
    FileAttributes(
      uuid = destDesc.uuid,
      location = absoluteDestPath,
      path = relativeDestPath,
      filename = destDesc.filename,
      mediaType = destDesc.mediaType,
      bytes = sourceAttr.bytes,
      digest = sourceAttr.digest,
      origin = sourceAttr.origin
    )
  }

  private def remoteDiskCopyPaths(destStorage: RemoteDiskStorage, copyDetails: NonEmptyList[RemoteDiskCopyDetails]) =
    copyDetails.map { cd =>
      val destDesc        = cd.destinationDesc
      val destinationPath = Uri.Path(intermediateFolders(destStorage.project, destDesc.uuid, destDesc.filename))
      val sourcePath      = cd.sourceAttributes.path
      RemoteDiskCopyPaths(cd.sourceBucket, sourcePath, destinationPath)
    }
}
