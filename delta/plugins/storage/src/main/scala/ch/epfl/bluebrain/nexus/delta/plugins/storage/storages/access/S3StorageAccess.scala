package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.access

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient

trait S3StorageAccess {

  def checkBucketExists(bucket: String): IO[Unit]

}

object S3StorageAccess {

  def apply(client: S3StorageClient): S3StorageAccess =
    (bucket: String) =>
      client.bucketExists(bucket).flatMap { exists =>
        IO.raiseUnless(exists)(StorageNotAccessible(s"Bucket $bucket does not exist"))
      }

}
