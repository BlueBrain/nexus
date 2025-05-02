package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.MediaType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{CopyOptions, LocalStackS3StorageClient, S3Helpers, S3OperationResult}
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

  private val textPlain       = MediaType.`text/plain`
  private val applicationJson = MediaType.`application/json`

  override def munitFixtures: Seq[AnyFixture[?]] = List(localStackS3Client)

  test("Copy a file containing special characters between buckets") {
    givenAnS3Bucket { bucket =>
      givenAnS3Bucket { targetBucket =>
        val options = CopyOptions(overwriteTarget = false, None)
        val key     = "/org/proj/9/f/0/3/2/4/f/e/0925_Rhi13.3.13 cell 1+2 (superficial).asc"
        givenAFileInABucket(bucket, key, fileContents) { _ =>
          for {
            result <- s3StorageClient.copyObject(bucket, key, targetBucket, key, options)
            head   <- s3StorageClient.headObject(targetBucket, key)
          } yield {
            assertEquals(result, S3OperationResult.Success)
            assertEquals(head.fileSize, contentLength)
            assertEquals(head.mediaType, Some(textPlain))
          }
        }
      }
    }
  }

  test("Copy the file to its new location if none is already there without a content type") {
    givenAnS3Bucket { bucket =>
      val options = CopyOptions(overwriteTarget = false, None)
      givenAFileInABucket(bucket, fileContents) { key =>
        val newKey = genString()
        for {
          result <- s3StorageClient.copyObject(bucket, key, bucket, newKey, options)
          head   <- s3StorageClient.headObject(bucket, newKey)
        } yield {
          assertEquals(result, S3OperationResult.Success)
          assertEquals(head.fileSize, contentLength)
          assertEquals(head.mediaType, Some(textPlain))
        }
      }
    }
  }

  test("Copy the file to its new location if none is already there setting a content type") {
    givenAnS3Bucket { bucket =>
      val options = CopyOptions(overwriteTarget = false, Some(applicationJson))
      givenAFileInABucket(bucket, fileContents) { key =>
        val newKey = genString()
        for {
          result <- s3StorageClient.copyObject(bucket, key, bucket, newKey, options)
          head   <- s3StorageClient.headObject(bucket, newKey)
        } yield {
          assertEquals(result, S3OperationResult.Success)
          assertEquals(head.fileSize, contentLength)
          assertEquals(head.mediaType, Some(applicationJson))
        }
      }
    }
  }

  test("Do not overwrite an existing object") {
    givenAnS3Bucket { bucket =>
      val options = CopyOptions(overwriteTarget = false, Some(applicationJson))
      givenFilesInABucket(bucket, fileContents, anotherContent) { case (sourceKey, existingTargetKey) =>
        for {
          result <- s3StorageClient.copyObject(bucket, sourceKey, bucket, existingTargetKey, options)
          head   <- s3StorageClient.headObject(bucket, existingTargetKey)
        } yield {
          val clue = "The file should not have been overwritten"
          assertEquals(result, S3OperationResult.AlreadyExists)
          assertEquals(head.fileSize, anotherContentLength, clue)
          assertEquals(head.mediaType, Some(textPlain), clue)
        }
      }
    }
  }

  test("Overwrite an existing object") {
    givenAnS3Bucket { bucket =>
      val options = CopyOptions(overwriteTarget = true, Some(applicationJson))
      givenFilesInABucket(bucket, fileContents, anotherContent) { case (sourceKey, existingTargetKey) =>
        for {
          result <- s3StorageClient.copyObject(bucket, sourceKey, bucket, existingTargetKey, options)
          head   <- s3StorageClient.headObject(bucket, existingTargetKey)
        } yield {
          val clue = "The file should have been overwritten"
          assertEquals(result, S3OperationResult.Success)
          assertEquals(head.fileSize, contentLength, clue)
          assertEquals(head.mediaType, Some(applicationJson), clue)
        }
      }
    }
  }

  test("Update the content type of an existing object") {
    givenAnS3Bucket { bucket =>
      givenAFileInABucket(bucket, fileContents) { key =>
        for {
          result <- s3StorageClient.updateContentType(bucket, key, applicationJson)
          head   <- s3StorageClient.headObject(bucket, key)
        } yield {
          assertEquals(result, S3OperationResult.Success)
          assertEquals(head.mediaType, Some(applicationJson))
        }
      }
    }
  }

  test("Do not update the content type of an existing object if it is already set to this value") {
    val originalContentType = textPlain
    givenAnS3Bucket { bucket =>
      givenAFileInABucket(bucket, fileContents) { key =>
        for {
          result <- s3StorageClient.updateContentType(bucket, key, originalContentType)
          head   <- s3StorageClient.headObject(bucket, key)
        } yield {
          assertEquals(result, S3OperationResult.AlreadyExists)
          assertEquals(head.mediaType, Some(originalContentType))
        }
      }
    }
  }

}
