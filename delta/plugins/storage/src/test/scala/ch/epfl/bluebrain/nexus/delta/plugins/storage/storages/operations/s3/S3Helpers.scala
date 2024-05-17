package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.testkit.Generators
import fs2.Stream
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, DeleteBucketRequest, DeleteObjectRequest}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.ListHasAsScala

trait S3Helpers { self: Generators =>

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
  )(implicit client: S3StorageClient): IO[Unit] = {
    val bytes = contents.getBytes(StandardCharsets.UTF_8)
    val key   = genString()
    client.uploadFile(Stream.emit(ByteBuffer.wrap(bytes)), bucket, key, bytes.length.toLong) >> test(key)
  }

  def givenFilesInABucket(bucket: String, contents1: String, contents2: String)(
      test: (String, String) => IO[Unit]
  )(implicit client: S3StorageClient): IO[Unit] = {
    val bytes1 = contents1.getBytes(StandardCharsets.UTF_8)
    val key1   = genString()
    val bytes2 = contents2.getBytes(StandardCharsets.UTF_8)
    val key2   = genString()
    for {
      _ <- client.uploadFile(Stream.emit(ByteBuffer.wrap(bytes1)), bucket, key1, bytes1.length.toLong)
      _ <- client.uploadFile(Stream.emit(ByteBuffer.wrap(bytes2)), bucket, key2, bytes2.length.toLong)
      _ <- test(key1, key2)
    } yield ()
  }

}
