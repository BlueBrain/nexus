package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.MoveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import monix.bio.IO
import monix.execution.Scheduler

class RemoteDiskStorageMoveFile(storage: RemoteDiskStorage)(implicit as: ActorSystem, sc: Scheduler) extends MoveFile {
  implicit private val cred: Option[AuthToken] = storage.value.credentials.map(secret => AuthToken(secret.value))
  private val client: RemoteDiskStorageClient  = RemoteDiskStorageClient(storage.value.endpoint)

  override def apply(sourcePath: Uri.Path, description: FileDescription): IO[MoveFileRejection, FileAttributes] = {
    val destinationPath = intermediateFolders(storage.project, description.uuid, description.filename)
    client.moveFile(storage.value.folder, sourcePath, destinationPath).map {
      case RemoteDiskStorageFileAttributes(location, bytes, digest, mediaType) =>
        val usedMediaType = description.mediaType.getOrElse(mediaType)
        FileAttributes(description.uuid, location, destinationPath, description.filename, usedMediaType, bytes, digest)
    }

  }
}
