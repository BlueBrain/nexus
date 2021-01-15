package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import monix.bio.IO
import monix.execution.Scheduler

class RemoteDiskStorageSaveFile(storage: RemoteDiskStorage)(implicit as: ActorSystem, sc: Scheduler) extends SaveFile {
  implicit private val cred: Option[AuthToken] = storage.value.credentials.map(secret => AuthToken(secret.value))
  private val client: RemoteDiskStorageClient  = RemoteDiskStorageClient(storage.value.endpoint)

  override def apply(
      description: FileDescription,
      source: AkkaSource
  ): IO[SaveFileRejection, FileAttributes] = {
    val path = intermediateFolders(storage.project, description.uuid, description.filename)
    client.createFile(storage.value.folder, path, source).map {
      case RemoteDiskStorageFileAttributes(location, bytes, digest, mediaType) =>
        FileAttributes(
          uuid = description.uuid,
          location = location,
          path = path,
          filename = description.filename,
          mediaType = description.mediaType.getOrElse(mediaType),
          bytes = bytes,
          digest = digest,
          origin = Client
        )
    }
  }
}
