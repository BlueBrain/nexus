package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import fs2.aws.s3.models.Models.{BucketName, FileKey}
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm

trait FileCopier {

  def copyFile(path: Uri.Path): IO[Unit]

}

object FileCopier {

  def apply(
      s3StorageClient: S3StorageClient,
      importBucket: BucketName,
      targetBucket: BucketName
  ): FileCopier =
    (path: Uri.Path) => {
      def refineString(str: String) =
        refineV[NonEmpty](str).leftMap(e => new IllegalArgumentException(e))

      val fileKey = IO.fromEither(refineString(path.toString).map(FileKey))

      fileKey.flatMap { key =>
        // TODO: Check if we only use SHA256 or not? If not we need to pass the right algo
        s3StorageClient.copyObject(importBucket, key, targetBucket, key, ChecksumAlgorithm.SHA256)
      }.void
    }

  def apply(): FileCopier =
    (_: Uri.Path) => IO.unit

}
