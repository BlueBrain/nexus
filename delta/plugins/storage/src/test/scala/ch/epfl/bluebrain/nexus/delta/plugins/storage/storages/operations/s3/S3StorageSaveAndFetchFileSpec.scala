package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, Secret}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.{FileNotFound, UnexpectedFetchError}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection.{FileAlreadyExists, UnexpectedSaveError}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.MinioDocker._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.MinioSpec._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.Json
import monix.execution.Scheduler
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import software.amazon.awssdk.regions.Region

import java.util.UUID

@DoNotDiscover
class S3StorageSaveAndFetchFileSpec
    extends TestKit(ActorSystem("S3StorageSaveAndFetchFileSpec"))
    with AnyWordSpecLike
    with AkkaSourceHelpers
    with Matchers
    with IOValues
    with BeforeAndAfterAll {

  implicit private val sc: Scheduler = Scheduler.global

  private val storageValue = S3StorageValue(
    default = false,
    algorithm = DigestAlgorithm.default,
    bucket = "bucket2",
    endpoint = Some(s"http://$VirtualHost:$MinioServicePort"),
    accessKey = Some(Secret(AccessKey)),
    secretKey = Some(Secret(SecretKey)),
    region = Some(Region.EU_CENTRAL_1),
    readPermission = read,
    writePermission = write,
    maxFileSize = 20
  )

  override protected def beforeAll(): Unit =
    createBucket(storageValue).hideErrors.accepted

  override protected def afterAll(): Unit =
    deleteBucket(storageValue).hideErrors.accepted

  "S3Storage operations" should {
    val iri = iri"http://localhost/s3"

    val uuid     = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
    val project  = ProjectRef.unsafe("org", "project")
    val filename = "myfile.txt"

    val storage = S3Storage(iri, project, storageValue, Map.empty, Secret(Json.obj()))
    val content = "file content"
    val source  = Source(content.map(c => ByteString(c.toString)))

    val digest     =
      ComputedDigest(DigestAlgorithm.default, "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c")
    val attributes = FileAttributes(
      uuid,
      s"http://bucket2.$VirtualHost:$MinioServicePort/org/project/8/0/4/9/b/a/9/0/myfile.txt",
      Uri.Path("org/project/8/0/4/9/b/a/9/0/myfile.txt"),
      "myfile.txt",
      `text/plain(UTF-8)`,
      12,
      digest,
      Client
    )

    "fail saving a file to a bucket on wrong credentials" in {
      val description  = FileDescription(uuid, filename, Some(`text/plain(UTF-8)`))
      val otherStorage = storage.copy(value = storage.value.copy(accessKey = Some(Secret("wrong"))))
      otherStorage.saveFile(description, source).rejectedWith[UnexpectedSaveError]
    }

    "save a file to a bucket" in {
      val description = FileDescription(uuid, filename, Some(`text/plain(UTF-8)`))
      storage.saveFile(description, source).accepted shouldEqual attributes
    }

    "fetch a file from a bucket" in {
      val sourceFetched = storage.fetchFile(attributes).accepted
      consume(sourceFetched) shouldEqual content
    }

    "fail fetching a file to a bucket on wrong credentials" in {
      val otherStorage = storage.copy(value = storage.value.copy(accessKey = Some(Secret("wrong"))))
      otherStorage.fetchFile(attributes).rejectedWith[UnexpectedFetchError]
    }

    "fail fetching a file that does not exist" in {
      storage.fetchFile(attributes.copy(path = Uri.Path("other.txt"))).rejectedWith[FileNotFound]
    }

    "fail attempting to save the same file again" in {
      val description = FileDescription(uuid, "myfile.txt", Some(`text/plain(UTF-8)`))
      storage.saveFile(description, source).rejectedWith[FileAlreadyExists]
    }
  }
}
