package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDetails, FileAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.CopyFiles
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskCopyPaths

class RemoteDiskStorageCopyFiles(
    destStorage: RemoteDiskStorage,
    client: RemoteDiskStorageClient
) extends CopyFiles {

  private val logger = Logger[RemoteDiskStorageCopyFiles]

  override def apply(copyDetails: NonEmptyList[CopyFileDetails]): IO[NonEmptyList[FileAttributes]] = {
    val maybePaths = copyDetails.traverse { cd =>
      val destinationPath =
        Uri.Path(intermediateFolders(destStorage.project, cd.destinationDesc.uuid, cd.destinationDesc.filename))
      val sourcePath      = cd.sourceAttributes.path

      val thingy = cd.sourceStorage.storageValue match {
        case remote: StorageValue.RemoteDiskStorageValue => IO(remote.folder)
        case other                                       => IO.raiseError(new Exception(s"Invalid storage type for remote copy: $other"))
      }
      thingy.map(sourceBucket => RemoteDiskCopyPaths(sourceBucket, sourcePath, destinationPath))
    }

    maybePaths.flatMap { paths =>
      logger.info(s"DTBDTB REMOTE doing copy with ${destStorage.value.folder} and $paths") >>
        client.copyFiles(destStorage.value.folder, paths)(destStorage.value.endpoint).flatMap { destPaths =>
          logger.info(s"DTBDTB REMOTE received destPaths ${destPaths}").as {
            copyDetails.zip(paths).zip(destPaths).map { case ((cd, x), destinationPath) =>
              FileAttributes(
                uuid = cd.destinationDesc.uuid,
                location = destinationPath,
                path = x.destPath,
                filename = cd.destinationDesc.filename,
                mediaType = cd.destinationDesc.mediaType,
                bytes = cd.sourceAttributes.bytes,
                digest = cd.sourceAttributes.digest,
                origin = cd.sourceAttributes.origin
              )
            }
          }
        }
    }
  }

}
