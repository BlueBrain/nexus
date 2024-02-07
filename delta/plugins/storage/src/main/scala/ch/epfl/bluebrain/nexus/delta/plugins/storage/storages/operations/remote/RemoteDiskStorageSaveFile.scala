package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes

class RemoteDiskStorageSaveFile(storage: RemoteDiskStorage, client: RemoteDiskStorageClient)(implicit uuidf: UUIDF)
    extends SaveFile {

  override def apply(
      filename: String,
      entity: BodyPartEntity
  ): IO[FileStorageMetadata] = {
    for {
      uuid                                                        <- uuidf()
      path                                                         = Uri.Path(intermediateFolders(storage.project, uuid, filename))
      RemoteDiskStorageFileAttributes(location, bytes, digest, _) <-
        client.createFile(storage.value.folder, path, entity)
    } yield {
      FileStorageMetadata(
        uuid = uuid,
        bytes = bytes,
        digest = digest,
        origin = Client,
        location = location,
        path = path
      )
    }
  }
}
