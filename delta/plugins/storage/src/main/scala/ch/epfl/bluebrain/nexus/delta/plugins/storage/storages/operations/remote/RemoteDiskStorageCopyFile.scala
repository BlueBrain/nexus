package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDetails, FileAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.CopyFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient

class RemoteDiskStorageCopyFile(
    storage: RemoteDiskStorage,
    client: RemoteDiskStorageClient
) extends CopyFile {

  override def apply(copyDetails: NonEmptyList[CopyFileDetails]): IO[NonEmptyList[FileAttributes]] = {
    val thing = copyDetails.map { cd =>
      val destinationPath =
        Uri.Path(intermediateFolders(storage.project, cd.destinationDesc.uuid, cd.destinationDesc.filename))
      val sourcePath      = cd.sourceAttributes.location
      (sourcePath, destinationPath)
    }

    client.copyFile(storage.value.folder, thing)(storage.value.endpoint).map { destPaths =>
      copyDetails.zip(destPaths).map { case (cd, destinationPath) =>
        FileAttributes(
          uuid = cd.destinationDesc.uuid,
          location = destinationPath,
          path = destinationPath.path,
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
