package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.LinkFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes

class RemoteDiskStorageLinkFile(storage: RemoteDiskStorage, client: RemoteDiskStorageClient) extends LinkFile {

  private val logger = Logger[RemoteDiskStorageLinkFile]

  def apply(sourcePath: Uri.Path, description: FileDescription): IO[FileAttributes] = {
    val destinationPath = Uri.Path(intermediateFolders(storage.project, description.uuid, description.filename))
    logger.info(s"DTBDTB doing link file with ${storage.value.folder}, source $sourcePath, dest $destinationPath") >>
      client.moveFile(storage.value.folder, sourcePath, destinationPath)(storage.value.endpoint).map {
        case RemoteDiskStorageFileAttributes(location, bytes, digest, _) =>
          FileAttributes(
            uuid = description.uuid,
            location = location,
            path = destinationPath,
            filename = description.filename,
            mediaType = description.mediaType,
            bytes = bytes,
            digest = digest,
            origin = Storage
          )
      }

  }
}
