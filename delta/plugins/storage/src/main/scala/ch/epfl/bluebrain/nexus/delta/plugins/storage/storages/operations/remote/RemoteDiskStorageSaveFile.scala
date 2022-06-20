package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.BodyPartEntity
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.AuthToken
import monix.bio.IO

class RemoteDiskStorageSaveFile(storage: RemoteDiskStorage)(implicit
    config: StorageTypeConfig,
    httpClient: HttpClient,
    as: ActorSystem
) extends SaveFile {
  implicit private val cred: Option[AuthToken] = storage.value.authToken(config)
  private val client: RemoteDiskStorageClient  = new RemoteDiskStorageClient(storage.value.endpoint)

  override def apply(
      description: FileDescription,
      entity: BodyPartEntity
  ): IO[SaveFileRejection, FileAttributes] = {
    val path = intermediateFolders(storage.project, description.uuid, description.filename)
    client.createFile(storage.value.folder, path, entity).map {
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
