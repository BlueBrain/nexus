package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.testkit.Generators
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, DeleteBucketRequest, DeleteObjectRequest}

import scala.jdk.CollectionConverters.ListHasAsScala

trait S3Helpers { self: Generators =>

  def givenAnS3Bucket(
      test: String => IO[Unit]
  )(implicit client: S3StorageClient, fs2Client: S3AsyncClientOp[IO]): IO[Unit] = {
    val bucket = genString()
    fs2Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build) >>
      test(bucket) >>
      emptyBucket(bucket) >>
      fs2Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build).void
  }

  def emptyBucket(bucket: String)(implicit client: S3StorageClient, fs2Client: S3AsyncClientOp[IO]): IO[Unit] =
    client
      .listObjectsV2(bucket)
      .flatMap { resp =>
        val keys: List[String] = resp.contents.asScala.map(_.key()).toList
        keys.traverse(deleteObject(bucket, _))
      }
      .void

  def deleteObject(bucket: String, key: String)(implicit client: S3AsyncClientOp[IO]): IO[Unit] =
    client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()).void

}
