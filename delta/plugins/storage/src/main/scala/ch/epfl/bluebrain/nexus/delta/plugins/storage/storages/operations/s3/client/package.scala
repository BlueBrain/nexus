package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import cats.MonadThrow
import cats.effect.IO
import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import cats.syntax.all._
import fs2.Stream
import fs2.aws.s3.models.Models.{BucketName, FileKey}

package object client {

  type StreamIO[A] = Stream[IO, A]

  def parseNonEmptyString[F[_]: MonadThrow](s: String): F[String Refined NonEmpty] =
    MonadThrow[F].fromEither(refineV[NonEmpty](s).leftMap(e => new IllegalArgumentException(e)))

  def parseBucket[F[_]: MonadThrow](bucket: String): F[BucketName] = parseNonEmptyString(bucket).map(BucketName)

  def parseFileKey[F[_]: MonadThrow](fileKey: String): F[FileKey] = parseNonEmptyString(fileKey).map(FileKey)
}
