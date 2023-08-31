package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.BodyPartEntity
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import monix.bio.IO

class RemoteDiskStorageSaveFile(storage: RemoteDiskStorage, client: RemoteDiskStorageClient) extends SaveFile {

  override def apply(
      description: FileDescription,
      entity: BodyPartEntity
  ): IO[SaveFileRejection, FileAttributes] = {
    val path = intermediateFolders(storage.project, description.uuid, description.filename)
    client.createFile(storage.value.folder, path, entity)(storage.value.endpoint).map {
      case RemoteDiskStorageFileAttributes(location, bytes, digest, mediaType) =>
        FileAttributes(
          uuid = description.uuid,
          location = location,
          path = path,
          filename = description.filename,
          mediaType = description.mediaType orElse Some(mediaType),
          bytes = bytes,
          digest = digest,
          origin = Client
        )
    }
  }
}
