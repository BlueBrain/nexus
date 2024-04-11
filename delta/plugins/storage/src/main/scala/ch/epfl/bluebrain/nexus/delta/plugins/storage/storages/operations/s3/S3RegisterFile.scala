package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient

trait S3RegisterFile  {
  def register(bucket: String, path: Uri.Path): IO[FileStorageMetadata]
}
object S3RegisterFile {
  def mk(client: S3StorageClient): S3RegisterFile = (_, _) => {
    client.baseEndpoint >> IO(???)
  }
}
