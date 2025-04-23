package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.ContentTypes
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileDataHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.testkit.Generators
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, DeleteBucketRequest, DeleteObjectRequest}

import scala.jdk.CollectionConverters.ListHasAsScala

trait S3Helpers extends FileDataHelpers { self: Generators =>

  def givenAnS3Bucket[A](
      test: String => IO[A]
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

  def givenAFileInABucket(bucket: String, contents: String)(
      test: String => IO[Unit]
  )(implicit client: S3StorageClient): IO[Unit] =
    givenAFileInABucket(bucket, genString(), contents)(test)

  def givenAFileInABucket(bucket: String, key: String, contents: String)(
      test: String => IO[Unit]
  )(implicit client: S3StorageClient): IO[Unit] = {
    val put = PutObjectRequest(bucket, key, Some(ContentTypes.`text/plain(UTF-8)`), contents.length.toLong)
    client.uploadFile(put, streamData(contents)) >> test(key)
  }

  def givenFilesInABucket(bucket: String, contents1: String, contents2: String)(
      test: (String, String) => IO[Unit]
  )(implicit client: S3StorageClient): IO[Unit] = {
    val key1 = genString()
    val put1 = PutObjectRequest(bucket, key1, Some(ContentTypes.`text/plain(UTF-8)`), contents1.length.toLong)
    val key2 = genString()
    val put2 = PutObjectRequest(bucket, key2, Some(ContentTypes.`text/plain(UTF-8)`), contents2.length.toLong)
    for {
      _ <- client.uploadFile(put1, streamData(contents1))
      _ <- client.uploadFile(put2, streamData(contents2))
      _ <- test(key1, key2)
    } yield ()
  }

}
