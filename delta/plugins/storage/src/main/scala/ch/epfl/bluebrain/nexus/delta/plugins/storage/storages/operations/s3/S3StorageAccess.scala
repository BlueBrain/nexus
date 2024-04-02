package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient

final class S3StorageAccess(s3Client: S3StorageClient) {

  private val log = Logger[S3StorageAccess]

  def apply(bucket: String): IO[Unit] =
    s3Client
      .listObjectsV2(bucket)
      .redeemWith(
        err => IO.raiseError(StorageNotAccessible(err.getMessage)),
        response => log.info(s"S3 bucket $bucket contains ${response.keyCount()} objects")
      )
}
