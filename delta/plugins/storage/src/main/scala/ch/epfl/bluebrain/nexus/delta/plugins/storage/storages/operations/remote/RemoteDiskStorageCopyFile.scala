package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.CopyFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient

class RemoteDiskStorageCopyFile(
    storage: RemoteDiskStorage,
    client: RemoteDiskStorageClient
) extends CopyFile {

  def apply(source: FileAttributes, description: FileDescription): IO[FileAttributes] = {
    val destinationPath = Uri.Path(intermediateFolders(storage.project, description.uuid, description.filename))
    client.copyFile(storage.value.folder, source.location.path, destinationPath)(storage.value.endpoint).as {
      FileAttributes(
        uuid = description.uuid,
        location = source.location, // TODO what's the destination absolute path?
        path = destinationPath,
        filename = description.filename,
        mediaType = description.mediaType,
        bytes = source.bytes,
        digest = source.digest,
        origin = source.origin
      )
    }

  }

}
