package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{HttpEntity, Uri}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection.ResourceAlreadyExists
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.MinioSpec._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.minio.MinioDocker
import ch.epfl.bluebrain.nexus.testkit.minio.MinioDocker._
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}
import software.amazon.awssdk.regions.Region

import java.util.UUID

@DoNotDiscover
class S3StorageSaveAndFetchFileSpec(docker: MinioDocker)
    extends TestKit(ActorSystem("S3StorageSaveAndFetchFileSpec"))
    with CatsEffectSpec
    with AkkaSourceHelpers
    with StorageFixtures
    with BeforeAndAfterAll {

  private val iri      = iri"http://localhost/s3"
  private val uuid     = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
  private val project  = ProjectRef.unsafe("org", "project")
  private val filename = "myfile.txt"
  private val digest   =
    ComputedDigest(DigestAlgorithm.default, "e0ac3601005dfa1864f5392aabaf7d898b1b5bab854f1acb4491bcd806b76b0c")

  private var storageValue: S3StorageValue = _
  private var storage: S3Storage           = _
  private var attributes: FileAttributes   = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    storageValue = S3StorageValue(
      default = false,
      algorithm = DigestAlgorithm.default,
      bucket = "bucket2",
      endpoint = Some(docker.hostConfig.endpoint),
      region = Some(Region.EU_CENTRAL_1),
      readPermission = read,
      writePermission = write,
      maxFileSize = 20
    )
    createBucket(storageValue).accepted
    storage = S3Storage(iri, project, storageValue, Json.obj())
    attributes = FileAttributes(
      uuid,
      s"http://bucket2.$VirtualHost:${docker.hostConfig.port}/org/project/8/0/4/9/b/a/9/0/myfile.txt",
      Uri.Path("org/project/8/0/4/9/b/a/9/0/myfile.txt"),
      "myfile.txt",
      Some(`text/plain(UTF-8)`),
      12,
      digest,
      Client
    )
  }

  override protected def afterAll(): Unit = {
    deleteBucket(storageValue).accepted
    super.afterAll()
  }

  "S3Storage operations" should {
    val content = "file content"
    val entity  = HttpEntity(content)

    "save a file to a bucket" in {
      val description = FileDescription(uuid, filename, Some(`text/plain(UTF-8)`))
      storage.saveFile(config).apply(description, entity).accepted shouldEqual attributes
    }

    "fetch a file from a bucket" in {
      val sourceFetched = storage.fetchFile(config).apply(attributes).accepted
      consume(sourceFetched) shouldEqual content
    }

    "fail fetching a file that does not exist" in {
      storage.fetchFile(config).apply(attributes.copy(path = Uri.Path("other.txt"))).rejectedWith[FileNotFound]
    }

    "fail attempting to save the same file again" in {
      val description = FileDescription(uuid, "myfile.txt", Some(`text/plain(UTF-8)`))
      storage.saveFile(config).apply(description, entity).rejectedWith[ResourceAlreadyExists]
    }
  }
}
