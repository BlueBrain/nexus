package ch.epfl.bluebrain.nexus.kg.storage

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import java.util.UUID

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{BucketAccess, S3Attributes}
import akka.stream.scaladsl.{FileIO, Sink}
import cats.effect.{ContextShift, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.kg.KgError.{DownstreamServiceError, RemoteFileNotFound}
import ch.epfl.bluebrain.nexus.kg.resources.Id
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.file.File.{Digest, FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.kg.storage.Storage.{S3Credentials, S3Settings, S3Storage}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.util._
import izumi.distage.model.definition.StandardAxis
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.TestConfig.ParallelLevel
import izumi.distage.testkit.scalatest.DistageSpecScalatest

import scala.concurrent.{ExecutionContext, Future}

class S3StorageOperationsSpec
    extends DistageSpecScalatest[IO]
    with ShouldMatchers
    with Randomness
    with Resources
    with DistageSpecSugar {

  private val readS3     = Permission.unsafe("s3/read")
  private val writeS3    = Permission.unsafe("s3/write")
  private val projectRef = ProjectRef(UUID.randomUUID)
  private val fileUuid   = UUID.randomUUID
  private val desc       = FileDescription(fileUuid, "my s3.json", Some(`text/plain(UTF-8)`))
  private val filePath   = "/storage/s3.json"
  private val path       = Paths.get(getClass.getResource(filePath).toURI)
  private val uriPath    = Uri.Path(mangle(projectRef, fileUuid, "my s3.json"))
  private val digest     = Digest("MD5", "5d3c675f85ffb2da9a8141ccd45bd6c6")

  implicit private val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  private def wrapFuture[A](future: => Future[A]): IO[A] =
    IO.fromFuture(IO(future))

  class TestFactory(port: Int)(implicit val as: ActorSystem, val mt: Materializer) {
    val base: Uri       = s"http://${MinioDocker.virtualHost}:$port"
    val bucketBase: Uri = s"http://bucket.${MinioDocker.virtualHost}:$port"

    val storage: S3Storage =
      // format: off
      S3Storage(projectRef, url"https://example.com", 1L, deprecated = false, default = true, "MD5", "bucket", S3Settings(Some(S3Credentials(MinioDocker.accessKey, MinioDocker.secretKey)), Some(base.toString), None), readS3, writeS3, 1024L)
    // format: on

    implicit private val attributes = S3Attributes.settings(storage.toAlpakkaSettings)

    val verify = new S3StorageOperations.Verify[IO](storage)
    val save   = new S3StorageOperations.Save[IO](storage)
    val fetch  = new S3StorageOperations.Fetch[IO](storage)
    val link   = new S3StorageOperations.Link[IO](storage)

    def createBucket: IO[Done] =
      wrapFuture(S3.checkIfBucketExists(storage.bucket)).flatMap {
        case BucketAccess.NotExists => wrapFuture(S3.makeBucket(storage.bucket))
        case _                      => IO.pure(Done)
      }

    def deleteBucket: IO[Done] =
      wrapFuture {
        S3.listBucket(storage.bucket, None)
          .withAttributes(attributes)
          .flatMapConcat { content =>
            S3.deleteObject(storage.bucket, URLDecoder.decode(content.getKey, UTF_8.toString))
              .withAttributes(attributes)
          }
          .run()
      } >> wrapFuture(S3.deleteBucket(storage.bucket))

  }

  override protected def config: TestConfig =
    TestConfig(
      pluginConfig = PluginConfig.empty,
      activation = StandardAxis.testDummyActivation,
      parallelTests = ParallelLevel.Sequential,
      moduleOverrides = new DistageModuleDef("S3StorageOperationsSpec") {
        include(MinioDockerModule[IO])
        make[TestFactory].from { (c: MinioDocker.Container, mt: Materializer, as: ActorSystem) =>
          val port = c.availablePorts.first(MinioDocker.primaryPort)
          new TestFactory(port.port)(as, mt)
        }
      },
      configBaseName = "s3-storage-test"
    )

  "A S3StorageOperations" should {

    "verify bucket exists" in { (tf: TestFactory) =>
      for {
        _      <- tf.createBucket
        result <- tf.verify.apply
        _       = result shouldEqual Right(())
      } yield ()
    }

    "fail verifying when bucket does not exists" in { (tf: TestFactory) =>
      import tf.as
      val verify = new S3StorageOperations.Verify[IO](tf.storage.copy(bucket = "bucket2"))
      for {
        result <- verify.apply
        _       = result shouldEqual Left("Error accessing S3 bucket 'bucket2': The specified bucket does not exist")
      } yield ()
    }

    "save file" in { (tf: TestFactory) =>
      val resid = Id(projectRef, url"http://example.com/id")
      val loc   = Uri(s"${tf.bucketBase}/$uriPath")

      for {
        attr <- tf.save(resid, desc, FileIO.fromPath(path))
        _     = attr shouldEqual FileAttributes(fileUuid, loc, uriPath, "my s3.json", `text/plain(UTF-8)`, 263L, digest)
      } yield ()
    }

    "fail saving when bucket does not exists" in { (tf: TestFactory) =>
      import tf.as
      val resid       = Id(projectRef, url"http://example.com/id")
      val save        = new S3StorageOperations.Save[IO](tf.storage.copy(bucket = "bucket2"))
      val expectedErr =
        "Error uploading S3 object with filename 'my s3.json' in bucket 'bucket2': The specified bucket does not exist"

      for {
        _ <- save(resid, desc, FileIO.fromPath(path)).passWhenError(DownstreamServiceError(expectedErr))
      } yield ()
    }

    "download file" in { (tf: TestFactory) =>
      import tf.mt
      val loc = Uri(s"${tf.bucketBase}/$uriPath")

      for {
        downloaded     <- tf.fetch(uriPath, loc)
        downloadedByte <- wrapFuture(downloaded.runWith(Sink.head))
        _               = downloadedByte.decodeString(UTF_8) shouldEqual contentOf(filePath)
      } yield ()
    }

    "fail downloading a file that does not exists" in { (tf: TestFactory) =>
      val nonExistPath = Uri.Path(mangle(projectRef, fileUuid, "nonexists.json"))
      val loc          = Uri(s"${tf.bucketBase}/$nonExistPath")

      for {
        _ <- tf.fetch(nonExistPath, loc).passWhenError(RemoteFileNotFound(loc))
      } yield ()

    }

    "fail downloading when bucket does not exists" in { (tf: TestFactory) =>
      import tf.as
      val fetch       = new S3StorageOperations.Fetch[IO](tf.storage.copy(bucket = "bucket2"))
      val loc         = Uri(s"${tf.bucketBase}/$uriPath")
      val expectedErr =
        s"Error fetching S3 object with key '$uriPath' in bucket 'bucket2': The specified bucket does not exist"

      for {
        _ <- fetch(uriPath, loc).passWhenError(DownstreamServiceError(expectedErr))
      } yield ()
    }

    "link file" in { (tf: TestFactory) =>
      val resid = Id(projectRef, url"http://example.com/linked/id")
      val loc   = Uri(s"${tf.bucketBase}/$uriPath")

      for {
        attr <- tf.link(resid, FileDescription(fileUuid, "file.json", Some(`text/plain(UTF-8)`)), uriPath)
        _     = attr shouldEqual FileAttributes(fileUuid, loc, uriPath, "file.json", `text/plain(UTF-8)`, 263L, digest)
      } yield ()
    }

    "fail linking a file that does not exists" in { (tf: TestFactory) =>
      val resid        = Id(projectRef, url"http://example.com/linked/id")
      val nonExistPath = Uri.Path(mangle(projectRef, fileUuid, "nonexists.json"))
      val loc          = Uri(s"${tf.bucketBase}/$nonExistPath")

      for {
        _ <- tf.link(resid, FileDescription(fileUuid, "nonexists.json", Some(`text/plain(UTF-8)`)), nonExistPath)
               .passWhenError(RemoteFileNotFound(loc))
      } yield ()
    }

    "fail linking when bucket does not exists" in { (tf: TestFactory) =>
      import tf.as
      val link        = new S3StorageOperations.Link[IO](tf.storage.copy(bucket = "bucket2"))
      val resid       = Id(projectRef, url"http://example.com/linked/id")
      val expectedErr =
        s"Error fetching S3 object with key '$uriPath' in bucket 'bucket2': The specified bucket does not exist"

      for {
        _ <- link(resid, FileDescription(fileUuid, "file.json", Some(`text/plain(UTF-8)`)), uriPath)
               .passWhenError(DownstreamServiceError(expectedErr))
      } yield ()
    }

    "cleanup" in { (tf: TestFactory) =>
      for {
        _ <- tf.deleteBucket
      } yield ()
    }
  }
}
