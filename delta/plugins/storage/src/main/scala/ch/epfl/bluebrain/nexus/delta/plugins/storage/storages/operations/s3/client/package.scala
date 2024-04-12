package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import cats.effect.IO
import eu.timepit.refined.types.string.NonEmptyString
import fs2.aws.s3.models.Models.{BucketName, FileKey}

package object client {

  def parseBucket(bucket: String): IO[BucketName] = IO(BucketName(NonEmptyString.unsafeFrom(bucket)))

  def parseFileKey(fileKey: String): IO[FileKey] = IO(FileKey(NonEmptyString.unsafeFrom(fileKey)))
}
