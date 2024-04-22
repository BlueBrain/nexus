package ch.epfl.bluebrain.nexus.ship.files

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import fs2.aws.s3.models.Models.{BucketName, FileKey}

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
        s3StorageClient.copyObject(importBucket, key, targetBucket, key)
      }.void
    }

  def apply(): FileCopier =
    (_: Uri.Path) => IO.unit

}
