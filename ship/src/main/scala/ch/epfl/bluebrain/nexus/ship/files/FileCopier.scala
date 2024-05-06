package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm

trait FileCopier {

  def copyFile(path: Uri.Path): IO[Unit]

}

object FileCopier {

  def apply(
      s3StorageClient: S3StorageClient,
      importBucket: String,
      targetBucket: String
  ): FileCopier =
    (path: Uri.Path) => {
      val key = path.toString

      // TODO: Check if we only use SHA256 or not? If not we need to pass the right algo
      s3StorageClient.copyObject(importBucket, key, targetBucket, key, ChecksumAlgorithm.SHA256).void
    }

  def apply(): FileCopier =
    (_: Uri.Path) => IO.unit

}
