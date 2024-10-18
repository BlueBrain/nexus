package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.ContentTypes
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
    val put   = PutObjectRequest(bucket, key, Some(ContentTypes.`text/plain(UTF-8)`), bytes.length.toLong)
    client.uploadFile(put, Stream.emit(ByteBuffer.wrap(bytes))) >> test(key)
  }

  def givenFilesInABucket(bucket: String, contents1: String, contents2: String)(
      test: (String, String) => IO[Unit]
  )(implicit client: S3StorageClient): IO[Unit] = {
    val bytes1 = contents1.getBytes(StandardCharsets.UTF_8)
    val key1   = genString()
    val put1   = PutObjectRequest(bucket, key1, Some(ContentTypes.`text/plain(UTF-8)`), bytes1.length.toLong)
    val bytes2 = contents2.getBytes(StandardCharsets.UTF_8)
    val key2   = genString()
    val put2   = PutObjectRequest(bucket, key2, Some(ContentTypes.`text/plain(UTF-8)`), bytes2.length.toLong)
    for {
      _ <- client.uploadFile(put1, Stream.emit(ByteBuffer.wrap(bytes1)))
      _ <- client.uploadFile(put2, Stream.emit(ByteBuffer.wrap(bytes2)))
      _ <- test(key1, key2)
    } yield ()
  }

}
