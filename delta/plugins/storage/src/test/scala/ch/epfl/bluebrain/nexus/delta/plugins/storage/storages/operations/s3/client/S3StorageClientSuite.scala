package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import akka.http.scaladsl.model.ContentTypes
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{CopyOptions, CopyResult, LocalStackS3StorageClient, S3Helpers}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import munit.AnyFixture

class S3StorageClientSuite extends NexusSuite with LocalStackS3StorageClient.Fixture with S3Helpers {

  implicit private lazy val (s3StorageClient: S3StorageClient, underlying: S3AsyncClientOp[IO], _: S3StorageConfig) =
    localStackS3Client()

  private val fileContents  = "file content"
  private val contentLength = fileContents.length.toLong

  private val anotherContent       = "Another content"
  private val anotherContentLength = anotherContent.length.toLong

  private val expectedContentType = ContentTypes.`text/plain(UTF-8)`
  private val contentType         = ContentTypes.`application/json`

  override def munitFixtures: Seq[AnyFixture[_]] = List(localStackS3Client)

  test("Copy the file to its new location if none is already there without a content type") {
    givenAnS3Bucket { bucket =>
      val options = CopyOptions(overwriteTarget = false, None)
      givenAFileInABucket(bucket, fileContents) { key =>
        val newKey = genString()
        for {
          result <- s3StorageClient.copyObject(bucket, key, bucket, newKey, options)
          head   <- s3StorageClient.headObject(bucket, newKey)
        } yield {
          assertEquals(result, CopyResult.Success)
          assertEquals(head.fileSize, contentLength)
          assertEquals(head.contentType, Some(expectedContentType))
        }
      }
    }
  }

  test("Copy the file to its new location if none is already there setting a content type") {
    givenAnS3Bucket { bucket =>
      val options = CopyOptions(overwriteTarget = false, Some(contentType))
      givenAFileInABucket(bucket, fileContents) { key =>
        val newKey = genString()
        for {
          result <- s3StorageClient.copyObject(bucket, key, bucket, newKey, options)
          head   <- s3StorageClient.headObject(bucket, newKey)
        } yield {
          assertEquals(result, CopyResult.Success)
          assertEquals(head.fileSize, contentLength)
          assertEquals(head.contentType, Some(contentType))
        }
      }
    }
  }

  test("Do not overwrite an existing object") {
    givenAnS3Bucket { bucket =>
      val options = CopyOptions(overwriteTarget = false, Some(contentType))
      givenFilesInABucket(bucket, fileContents, anotherContent) { case (sourceKey, existingTargetKey) =>
        for {
          result <- s3StorageClient.copyObject(bucket, sourceKey, bucket, existingTargetKey, options)
          head   <- s3StorageClient.headObject(bucket, existingTargetKey)
        } yield {
          val clue = "The file should not have been overwritten"
          assertEquals(result, CopyResult.AlreadyExists)
          assertEquals(head.fileSize, anotherContentLength, clue)
          assertEquals(head.contentType, Some(expectedContentType), clue)
        }
      }
    }
  }

  test("Overwrite an existing object") {
    givenAnS3Bucket { bucket =>
      val options = CopyOptions(overwriteTarget = true, Some(contentType))
      givenFilesInABucket(bucket, fileContents, anotherContent) { case (sourceKey, existingTargetKey) =>
        for {
          result <- s3StorageClient.copyObject(bucket, sourceKey, bucket, existingTargetKey, options)
          head   <- s3StorageClient.headObject(bucket, existingTargetKey)
        } yield {
          val clue = "The file should have been overwritten"
          assertEquals(result, CopyResult.Success)
          assertEquals(head.fileSize, contentLength, clue)
          assertEquals(head.contentType, Some(contentType), clue)
        }
      }
    }
  }

  test("Update the content type of an existing object") {
    givenAnS3Bucket { bucket =>
      givenAFileInABucket(bucket, fileContents) { key =>
        for {
          _    <- s3StorageClient.updateContentType(bucket, key, contentType)
          head <- s3StorageClient.headObject(bucket, key)
        } yield {
          assertEquals(head.contentType, Some(contentType))
        }
      }
    }
  }

}
