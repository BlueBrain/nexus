package ch.epfl.bluebrain.nexus.storage

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.MediaTypes.`application/x-tar`
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage.Rejection.{PathAlreadyExists, PathNotFound}
import ch.epfl.bluebrain.nexus.storage.StorageError.{PathInvalid, PermissionsFixingFailed}
import ch.epfl.bluebrain.nexus.storage.Storages.BucketExistence.{BucketDoesNotExist, BucketExists}
import ch.epfl.bluebrain.nexus.storage.Storages.DiskStorage
import ch.epfl.bluebrain.nexus.storage.Storages.PathExistence.{PathDoesNotExist, PathExists}
import ch.epfl.bluebrain.nexus.storage.attributes.AttributesCache
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.{DigestConfig, StorageConfig}
import ch.epfl.bluebrain.nexus.storage.utils.{EitherValues, IOEitherValues, Randomness}
import org.apache.commons.io.FileUtils
import org.mockito.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, Inspectors, OptionValues}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class DiskStorageSpec
    extends TestKit(ActorSystem("DiskStorageSpec"))
    with AnyWordSpecLike
    with Matchers
    with Randomness
    with IOEitherValues
    with BeforeAndAfterAll
    with EitherValues
    with OptionValues
    with Inspectors
    with IdiomaticMockito {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 15.milliseconds)

  implicit val ec: ExecutionContext = system.dispatcher

  val rootPath = Files.createTempDirectory("storage-test")
  val sConfig  = StorageConfig(rootPath, Paths.get("nexus"), fixerEnabled = true, Vector("/bin/echo"))
  val dConfig  = DigestConfig("SHA-256", 1L, 1, 1, 1.second)
  val cache    = mock[AttributesCache[IO]]
  val storage  = new DiskStorage[IO](sConfig, dConfig, cache)

  override def afterAll(): Unit = {
    FileUtils.deleteDirectory(rootPath.toFile)
  }

  trait AbsoluteDirectoryCreated {
    val name         = genString()
    val baseRootPath = rootPath.resolve(name)
    val basePath     = baseRootPath.resolve(sConfig.protectedDirectory)
    Files.createDirectories(rootPath.resolve(name).resolve(sConfig.protectedDirectory))
  }

  trait RelativeDirectoryCreated extends AbsoluteDirectoryCreated {
    val relativeDir                   = s"some/${genString()}"
    val relativeFileString            = s"$relativeDir/file.txt"
    val relativeFilePath              = Uri.Path(relativeFileString)
    val absoluteFilePath              = basePath.resolve(Paths.get(relativeFilePath.toString()))
    Files.createDirectories(absoluteFilePath.getParent)
    implicit val bucketExistsEvidence = BucketExists
    val alg                           = "SHA-256"
  }

  "A disk storage bundle" when {

    "checking storage" should {

      "fail when bucket directory does not exists" in {
        val name = genString()
        storage.exists(name) shouldBe a[BucketDoesNotExist]
      }

      "fail when bucket is not a directory" in {
        val name      = genString()
        val directory = Files.createDirectories(rootPath.resolve(name))
        Files.createFile(directory.resolve(sConfig.protectedDirectory))
        storage.exists(name) shouldBe a[BucketDoesNotExist]
      }

      "fail when the bucket goes out of the scope" in new AbsoluteDirectoryCreated {
        val invalid = List("one/two", "../other", "..", "one/two/three")
        forAll(invalid) {
          storage.exists(_) shouldBe a[BucketDoesNotExist]
        }
      }

      "pass" in new AbsoluteDirectoryCreated {
        storage.exists(name) shouldBe a[BucketExists]
      }
    }

    "checking path existence" should {

      "exists" in new AbsoluteDirectoryCreated {
        val relativeFileString = "some/file.txt"
        val relativeFilePath   = Uri.Path(relativeFileString)
        Files.createDirectories(basePath.resolve("some"))
        val filePath           = basePath.resolve(relativeFileString)
        Files.createFile(filePath)
        storage.pathExists(name, relativeFilePath) shouldBe a[PathExists]
      }

      "exists outside scope" in new AbsoluteDirectoryCreated {
        val relativeFileString = "../some/file.txt"
        val relativeFilePath   = Uri.Path(relativeFileString)
        Files.createDirectories(basePath.resolve("..").resolve("some").normalize())
        val filePath           = basePath.resolve(relativeFileString).normalize()
        Files.createFile(filePath)
        storage.pathExists(name, relativeFilePath) shouldBe a[PathDoesNotExist]
      }

      "not exists" in new RelativeDirectoryCreated {
        storage.pathExists(name, relativeFilePath) shouldBe a[PathDoesNotExist]
      }
    }

    "creating a file" should {

      "fail when destination is out of bucket scope" in new RelativeDirectoryCreated {
        val content                   = "some content"
        val source: AkkaSource        = Source.single(ByteString(content))
        implicit val pathDoesNotExist = PathDoesNotExist
        val relativePath              = Uri.Path("some/../../path")
        storage.createFile(name, relativePath, source).unsafeToFuture().failed.futureValue shouldEqual
          PathInvalid(name, relativePath)
      }

      "pass" in new RelativeDirectoryCreated {
        val content                   = "some content"
        val source: AkkaSource        = Source.single(ByteString(content))
        val digest                    = Digest("SHA-256", "290f493c44f5d63d06b374d0a5abd292fae38b92cab2fae5efefe1b0e9347f56")
        implicit val pathDoesNotExist = PathDoesNotExist
        storage.createFile(name, relativeFilePath, source).ioValue shouldEqual
          FileAttributes(s"file://${absoluteFilePath.toString}", 12L, digest, `text/plain(UTF-8)`)
      }
    }

    "linking" should {
      implicit val bucketExistsEvidence = BucketExists

      "fail when call to nexus-fixer fails" in new AbsoluteDirectoryCreated {
        val falseBinary  = if (new File("/bin/false").exists()) "/bin/false" else "/usr/bin/false"
        val badStorage   = new DiskStorage[IO](sConfig.copy(fixerCommand = Vector(falseBinary)), dConfig, cache)
        val file         = "some/folder/my !file.txt"
        val absoluteFile = baseRootPath.resolve(Paths.get(file))
        Files.createDirectories(absoluteFile.getParent)
        Files.write(absoluteFile, "something".getBytes(StandardCharsets.UTF_8))

        badStorage
          .moveFile(name, Uri.Path(file), Uri.Path(genString()))
          .failed[StorageError] shouldEqual PermissionsFixingFailed(absoluteFile.toString, "")
      }

      "fail when source does not exists" in new AbsoluteDirectoryCreated {
        val source = genString()
        storage.moveFile(name, Uri.Path(source), Uri.Path(genString())).rejected[PathNotFound] shouldEqual
          PathNotFound(name, Uri.Path(source))
      }

      "fail when source is inside protected directory" in new AbsoluteDirectoryCreated {
        val file         = sConfig.protectedDirectory.toString + "/other.txt"
        val absoluteFile = baseRootPath.resolve(Paths.get(file))
        Files.createDirectories(absoluteFile.getParent)
        Files.write(absoluteFile, "something".getBytes(StandardCharsets.UTF_8))

        storage.moveFile(name, Uri.Path(file), Uri.Path(genString())).rejected[PathNotFound] shouldEqual
          PathNotFound(name, Uri.Path(file))
      }

      "fail when destination already exists" in new AbsoluteDirectoryCreated {
        val file         = "some/folder/my !file.txt"
        val absoluteFile = baseRootPath.resolve(Paths.get(file.toString))
        Files.createDirectories(absoluteFile.getParent)
        Files.write(absoluteFile, "something".getBytes(StandardCharsets.UTF_8))

        val fileDest = basePath.resolve(Paths.get("my !file.txt"))
        Files.write(fileDest, "something".getBytes(StandardCharsets.UTF_8))
        storage
          .moveFile(name, Uri.Path(file), Uri.Path("my !file.txt"))
          .rejected[PathAlreadyExists] shouldEqual
          PathAlreadyExists(name, Uri.Path("my !file.txt"))
      }

      "fail when destination is out of bucket scope" in new AbsoluteDirectoryCreated {
        val file         = "some/folder/my !file.txt"
        val dest         = Uri.Path("../some/other path.txt")
        val absoluteFile = baseRootPath.resolve(Paths.get(file.toString))
        Files.createDirectories(absoluteFile.getParent)

        val content = "some content"
        Files.write(absoluteFile, content.getBytes(StandardCharsets.UTF_8))

        storage.moveFile(name, Uri.Path(file), dest).unsafeToFuture().failed.futureValue shouldEqual
          PathInvalid(name, dest)
        Files.exists(absoluteFile) shouldEqual true
      }

      "pass on file" in new AbsoluteDirectoryCreated {
        val file         = "some/folder/my !file.txt"
        val absoluteFile = baseRootPath.resolve(Paths.get(file.toString))
        Files.createDirectories(absoluteFile.getParent)

        val content = "some content"
        Files.write(absoluteFile, content.getBytes(StandardCharsets.UTF_8))

        storage.moveFile(name, Uri.Path(file), Uri.Path("some/other path.txt")).accepted shouldEqual
          FileAttributes(s"file://${basePath.resolve("some/other%20path.txt")}", 12L, Digest.empty, `text/plain(UTF-8)`)
        Files.exists(absoluteFile) shouldEqual false
        Files.exists(basePath.resolve("some/other path.txt")) shouldEqual true
      }

      "pass on directory" in new AbsoluteDirectoryCreated {
        val dir         = "some/folder"
        val absoluteDir = baseRootPath.resolve(Paths.get(dir.toString))
        Files.createDirectories(absoluteDir)

        val absoluteFile = absoluteDir.resolve(Paths.get("my !file.txt"))
        val content      = "some content"
        Files.write(absoluteFile, content.getBytes(StandardCharsets.UTF_8))

        val result      = storage.moveFile(name, Uri.Path(dir), Uri.Path("some/other")).accepted
        val resolvedDir = basePath.resolve("some/other")
        result shouldEqual FileAttributes(s"file://$resolvedDir", 12L, Digest.empty, `application/x-tar`)
        Files.exists(absoluteDir) shouldEqual false
        Files.exists(absoluteFile) shouldEqual false
        Files.exists(resolvedDir) shouldEqual true
        Files.exists(basePath.resolve("some/other/my !file.txt")) shouldEqual true
      }
    }

    "fetching" should {

      implicit val pathExistsEvidence = PathExists

      "fail when it does not exists" in new RelativeDirectoryCreated {
        storage.getFile(name, relativeFilePath).leftValue shouldEqual
          PathNotFound(name, relativeFilePath)
      }

      "pass with file" in new RelativeDirectoryCreated {
        val content                        = "some content"
        Files.write(absoluteFilePath, content.getBytes(StandardCharsets.UTF_8))
        val (resultSource, resultFilename) = storage.getFile(name, relativeFilePath).rightValue
        resultFilename.value shouldEqual "file.txt"
        resultSource.runWith(Sink.head).futureValue.decodeString(UTF_8) shouldEqual content
      }

      "pass with directory" in new RelativeDirectoryCreated {
        val content                        = "some content"
        Files.write(absoluteFilePath, content.getBytes(StandardCharsets.UTF_8))
        val (resultSource, resultFilename) = storage.getFile(name, Uri.Path(relativeDir)).rightValue
        resultFilename shouldEqual None
        resultSource.runFold("")(_ ++ _.utf8String).futureValue should include(content)
      }
    }

    "fetching attributes" should {

      implicit val pathExistsEvidence = PathExists

      "fail when it does not exists" in new RelativeDirectoryCreated {
        storage.getFile(name, relativeFilePath).leftValue shouldEqual
          PathNotFound(name, relativeFilePath)
      }

      "return the attributes" in new RelativeDirectoryCreated {
        val content            = "some content"
        Files.write(absoluteFilePath, content.getBytes(StandardCharsets.UTF_8))
        val expectedAttributes = FileAttributes(
          s"file://$absoluteFilePath",
          content.size.toLong,
          Digest(alg, genString()),
          `text/plain(UTF-8)`
        )
        cache.get(absoluteFilePath) shouldReturn IO(expectedAttributes)
        storage.getAttributes(name, relativeFilePath).ioValue shouldEqual expectedAttributes
      }
    }
  }

}
