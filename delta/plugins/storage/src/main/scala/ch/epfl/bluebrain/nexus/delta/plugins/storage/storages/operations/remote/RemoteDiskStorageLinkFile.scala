package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.LinkFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes

class RemoteDiskStorageLinkFile(storage: RemoteDiskStorage, client: RemoteDiskStorageClient)(implicit uuidf: UUIDF)
    extends LinkFile {

  def apply(sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata] = {
    for {
      uuid                                                        <- uuidf()
      destinationPath                                              = Uri.Path(intermediateFolders(storage.project, uuid, filename))
      RemoteDiskStorageFileAttributes(location, bytes, digest, _) <-
        client.moveFile(storage.value.folder, sourcePath, destinationPath)(storage.value.endpoint)
    } yield {
      FileStorageMetadata(
        uuid = uuid,
        bytes = bytes,
        digest = digest,
        origin = Storage,
        location = location,
        path = destinationPath
      )
    }
  }
}
