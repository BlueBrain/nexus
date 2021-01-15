package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, Secret}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection.FileAlreadyExists
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteStorageDocker.digest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.Json
import monix.execution.Scheduler
import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.{Files, Paths}
import java.util.UUID

class DiskStorageSaveFileSpec
    extends TestKit(ActorSystem("DiskStorageSaveFileSpec"))
    with AkkaSourceHelpers
    with AnyWordSpecLike
    with Matchers
    with IOValues
    with BeforeAndAfterAll {

  private val volume = Files.createTempDirectory("disk-access")
  private val file   = Paths.get(s"$volume/org/project/8/0/4/9/b/a/9/0/myfile.txt")

  implicit private val sc: Scheduler = Scheduler.global

  "A DiskStorage saving operations" should {
    val iri     = iri"http://localhost/disk"
    val project = ProjectRef.unsafe("org", "project")
    val value   = DiskStorageValue(default = true, DigestAlgorithm.default, volume, read, write, 10)
    val storage = DiskStorage(iri, project, value, Map.empty, Secret(Json.obj()))
    val uuid    = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
    val content = "file content"
    val source  = Source(content.map(c => ByteString(c.toString)))

    "save a file to a volume" in {
      val description = FileDescription(uuid, "myfile.txt", Some(`text/plain(UTF-8)`))

      val attributes = storage.saveFile(description, source).accepted

      Files.readString(file) shouldEqual content

      attributes shouldEqual
        FileAttributes(
          uuid,
          s"file://$file",
          Uri.Path("org/project/8/0/4/9/b/a/9/0/myfile.txt"),
          "myfile.txt",
          `text/plain(UTF-8)`,
          Files.size(file),
          digest,
          Client
        )

      consume(storage.fetchFile(attributes).accepted) shouldEqual content

    }

    "fail attempting to save the same file again" in {
      val description = FileDescription(uuid, "myfile.txt", Some(`text/plain(UTF-8)`))
      storage.saveFile(description, source).rejectedWith[FileAlreadyExists]
    }
  }

  override protected def afterAll(): Unit = FileUtils.deleteDirectory(volume.toFile)
}
