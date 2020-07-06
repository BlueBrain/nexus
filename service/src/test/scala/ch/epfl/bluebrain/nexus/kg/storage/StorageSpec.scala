package ch.epfl.bluebrain.nexus.kg.storage

import java.nio.file.Paths
import java.time.{Clock, Instant, ZoneId}
import java.util.regex.Pattern.quote

import akka.actor.ActorSystem
import akka.testkit.TestKit
import cats.implicits._
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.service.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.resources.Id
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.InvalidResourceFormat
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.storage.Storage._
import ch.epfl.bluebrain.nexus.kg.storage.StorageEncoder._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.util.{CirceEq, Resources}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import scala.concurrent.duration._

class StorageSpec
    extends TestKit(ActorSystem("StorageSpec"))
    with AnyWordSpecLike
    with Matchers
    with OptionValues
    with Resources
    with TestHelper
    with Inspectors
    with CirceEq {
  implicit private val clock = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  val readDisk               = Permission.unsafe("disk/read")
  val writeDisk              = Permission.unsafe("disk/write")
  val readS3                 = Permission.unsafe("s3/read")
  val writeS3                = Permission.unsafe("s3/write")

  private val appConfig = Settings(system).appConfig

  implicit private val storageConfig = appConfig.storage.copy(
    disk = DiskStorageConfig(Paths.get("/tmp/"), "SHA-256", readDisk, writeDisk, false, 1000L),
    remoteDisk = RemoteDiskStorageConfig(
      "http://example.com",
      "v1",
      Some(AccessToken(genString())),
      "SHA-256",
      read,
      write,
      true,
      2000L
    ),
    amazon = S3StorageConfig("MD5", readS3, writeS3, true, 3000L),
    password = "password",
    salt = "salt",
    fileAttrRetry = RetryStrategyConfig("linear", 300.millis, 5.minutes, 100, 1.second)
  )

  implicit val secretKey = storageConfig.derivedKey

  "A Storage" when {
    val iri        = url"http://example.com/id"
    val projectRef = ProjectRef(genUUID)
    val id         = Id(projectRef, iri)

    val diskStorage       = jsonContentOf("/storage/disk.json").appendContextOf(storageCtx)
    val s3Storage         = jsonContentOf("/storage/s3.json").appendContextOf(storageCtx)
    val remoteDiskStorage =
      jsonContentOf(
        "/storage/remoteDisk.json",
        Map(
          quote("{read}")   -> "myRead",
          quote("{write}")  -> "myWrite",
          quote("{folder}") -> "folder",
          quote("{cred}")   -> "credentials"
        )
      ).appendContextOf(storageCtx)

    val remoteDiskStorageDefault =
      remoteDiskStorage.removeKeys("credentials", "endpoint", "readPermissions", "writePermission", "readPermission")

    "constructing" should {
      val diskStoragePerms =
        jsonContentOf("/storage/diskPerms.json", Map(quote("{read}") -> "myRead", quote("{write}") -> "myWrite"))
          .appendContextOf(storageCtx)
      val s3Minimal        = jsonContentOf("/storage/s3-minimal.json").appendContextOf(storageCtx)

      "return a DiskStorage" in {
        val resource = simpleV(id, diskStorage, types = Set(nxv.Storage.value, nxv.DiskStorage.value))
        Storage(resource).rightValue shouldEqual
          DiskStorage(projectRef, iri, 1L, false, false, "SHA-256", Paths.get("/tmp"), readDisk, writeDisk, 1000L)
      }

      "return a DiskStorage with custom readPermission and writePermission" in {
        val resource      = simpleV(id, diskStoragePerms, types = Set(nxv.Storage.value, nxv.DiskStorage.value))
        val expectedRead  = Permission.unsafe("myRead")
        val expectedWrite = Permission.unsafe("myWrite")
        Storage(resource).rightValue shouldEqual
          DiskStorage(
            projectRef,
            iri,
            1L,
            false,
            false,
            "SHA-256",
            Paths.get("/tmp"),
            expectedRead,
            expectedWrite,
            30000L
          )
      }

      "return a default RemoteDiskStorage" in {
        val resource =
          simpleV(id, remoteDiskStorageDefault, types = Set(nxv.Storage.value, nxv.RemoteDiskStorage.value))
        Storage(resource).rightValue shouldEqual
          RemoteDiskStorage(
            projectRef,
            iri,
            1L,
            false,
            false,
            "SHA-256",
            storageConfig.remoteDisk.endpoint,
            storageConfig.remoteDisk.defaultCredentials.map(_.value),
            "folder",
            read,
            write,
            2000L
          )
      }

      "return a RemoteDiskStorage" in {
        val resource      = simpleV(id, remoteDiskStorage, types = Set(nxv.Storage.value, nxv.RemoteDiskStorage.value))
        val expectedRead  = Permission.unsafe("myRead")
        val expectedWrite = Permission.unsafe("myWrite")
        Storage(resource).rightValue shouldEqual
          RemoteDiskStorage(
            projectRef,
            iri,
            1L,
            false,
            false,
            "SHA-256",
            "http://example.com/some",
            Some("credentials"),
            "folder",
            expectedRead,
            expectedWrite,
            2000L
          )
      }

      "return a RemoteDiskStorage encrypted" in {
        val resource      = simpleV(id, remoteDiskStorage, types = Set(nxv.Storage.value, nxv.RemoteDiskStorage.value))
        val expectedRead  = Permission.unsafe("myRead")
        val expectedWrite = Permission.unsafe("myWrite")
        Storage(resource).rightValue.encrypt shouldEqual
          RemoteDiskStorage(
            projectRef,
            iri,
            1L,
            false,
            false,
            "SHA-256",
            "http://example.com/some",
            Some("credentials".encrypt),
            "folder",
            expectedRead,
            expectedWrite,
            2000L
          )
      }

      "return an S3Storage" in {
        val resource = simpleV(id, s3Minimal, types = Set(nxv.Storage.value, nxv.S3Storage.value))

        Storage(resource).rightValue shouldEqual
          S3Storage(
            projectRef,
            iri,
            1L,
            false,
            true,
            "MD5",
            "bucket",
            S3Settings(None, None, None),
            readS3,
            writeS3,
            3000L
          )
      }

      "return an S3Storage with credentials and region" in {
        val resource      = simpleV(id, s3Storage, types = Set(nxv.Storage.value, nxv.S3Storage.value))
        val settings      = S3Settings(Some(S3Credentials("access", "secret")), Some("endpoint"), Some("region"))
        val expectedRead  = Permission.unsafe("my/read")
        val expectedWrite = Permission.unsafe("my/write")
        Storage(resource).rightValue shouldEqual
          S3Storage(projectRef, iri, 1L, false, true, "MD5", "bucket", settings, expectedRead, expectedWrite, 3000L)
      }

      "return an S3Storage with encrypted credentials" in {
        val resource      = simpleV(id, s3Storage, types = Set(nxv.Storage.value, nxv.S3Storage.value))
        val settings      = S3Settings(
          Some(S3Credentials("MrAw2AGFs3T/+2M6nxOsuQ==", "Qa76lYhMOK9GPyTrxK26Jg==")),
          Some("endpoint"),
          Some("region")
        )
        val expectedRead  = Permission.unsafe("my/read")
        val expectedWrite = Permission.unsafe("my/write")
        Storage(resource).rightValue.encrypt shouldEqual
          S3Storage(projectRef, iri, 1L, false, true, "MD5", "bucket", settings, expectedRead, expectedWrite, 3000L)
      }

      "fail on DiskStorage when types are wrong" in {
        val resource = simpleV(id, diskStorage, types = Set(nxv.Storage.value))
        Storage(resource).leftValue shouldBe a[InvalidResourceFormat]
      }

      "fail on RemoteDiskStorage when types are wrong" in {
        val resource = simpleV(id, remoteDiskStorage, types = Set(nxv.Storage.value))
        Storage(resource).leftValue shouldBe a[InvalidResourceFormat]
      }

      "fail on S3Storage when types are wrong" in {
        val resource = simpleV(id, s3Storage, types = Set(nxv.S3Storage.value))
        Storage(resource).leftValue shouldBe a[InvalidResourceFormat]
      }

      "fail on DiskStorage when required parameters are not present" in {
        val resource =
          simpleV(id, diskStorage.removeKeys("volume"), types = Set(nxv.Storage.value, nxv.DiskStorage.value))
        Storage(resource).leftValue shouldBe a[InvalidResourceFormat]
      }

      "fail on S3Storage when required parameters are not present" in {
        val resource = simpleV(id, s3Storage.removeKeys("default"), types = Set(nxv.Storage.value, nxv.S3Storage.value))
        Storage(resource).leftValue shouldBe a[InvalidResourceFormat]
      }
    }

    "converting into json " should {
      "return the json representation for storages" in {

        val expectedRead    = Permission.unsafe("myRead")
        val expectedWrite   = Permission.unsafe("myWrite")
        // format: off
        val disk: Storage = DiskStorage(projectRef, iri, 1L, false, false, "SHA-256", Paths.get("/tmp"), readDisk, writeDisk, 1000L)
        val s3: Storage = S3Storage(projectRef, iri, 1L, false, true, "MD5", "bucket", S3Settings(None, None, None), readS3, writeS3, 3000L)
        val remote: Storage = RemoteDiskStorage(projectRef, iri, 1L, false, false, "SHA-256", "http://example.com/some", Some("credentials"), "folder", expectedRead, expectedWrite, 2000L)
        // format: on

        forAll(
          List(
            disk   -> jsonContentOf("/storage/disk-meta.json"),
            s3     -> jsonContentOf("/storage/s3-meta.json"),
            remote -> jsonContentOf("/storage/remoteDisk-meta.json")
          )
        ) {
          case (storage, expectedJson) =>
            val json =
              storage.asGraph.toJson(storageCtx.appendContextOf(resourceCtx)).rightValue.removeNestedKeys("@context")
            json should equalIgnoreArrayOrder(expectedJson.removeNestedKeys("@context"))
        }
      }
    }
  }

}
