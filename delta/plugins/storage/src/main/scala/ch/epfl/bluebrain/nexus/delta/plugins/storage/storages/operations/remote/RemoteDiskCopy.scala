package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient

trait RemoteDiskCopy {
  def copyFiles(destStorage: RemoteDiskStorage, copyDetails: NonEmptyList[RemoteDiskCopyDetails]): IO[NonEmptyList[FileAttributes]]
}

object RemoteDiskCopy {

  def mk(client: RemoteDiskStorageClient): RemoteDiskCopy = new RemoteDiskCopy {
    def copyFiles(destStorage: RemoteDiskStorage, copyDetails: NonEmptyList[RemoteDiskCopyDetails]): IO[NonEmptyList[FileAttributes]] = {
      val paths = copyDetails.map { cd =>
        val destDesc = cd.destinationDesc
        val destinationPath =
          Uri.Path(intermediateFolders(destStorage.project, destDesc.uuid, destDesc.filename))
        val sourcePath = cd.sourceAttributes.path
        (cd.sourceBucket, sourcePath, destinationPath)
      }

      client.copyFile(destStorage.value.folder, paths)(destStorage.value.endpoint).map { destPaths =>
        copyDetails.zip(paths).zip(destPaths).map { case ((cd, x), destinationPath) =>
          val destDesc = cd.destinationDesc
          val sourceAttr = cd.sourceAttributes
          FileAttributes(
            uuid = destDesc.uuid,
            location = destinationPath,
            path = x._3,
            filename = destDesc.filename,
            mediaType = destDesc.mediaType,
            bytes = sourceAttr.bytes,
            digest = sourceAttr.digest,
            origin = sourceAttr.origin
          )
        }
      }
    }
  }
}
