package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{HttpEntity, Uri}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection.ResourceAlreadyExists
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.remotestorage.RemoteStorageDocker
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json
import org.scalatest.BeforeAndAfterAll

import java.nio.file.{Files, Paths}
import java.util.UUID
import scala.reflect.io.Directory

class DiskStorageSaveFileSpec
    extends TestKit(ActorSystem("DiskStorageSaveFileSpec"))
    with AkkaSourceHelpers
    with CatsEffectSpec
    with BeforeAndAfterAll {

  private val volume = AbsolutePath(Files.createTempDirectory("disk-access")).rightValue
  private val file   = AbsolutePath(Paths.get(s"$volume/org/project/8/0/4/9/b/a/9/0/myfile.txt")).rightValue

  "A DiskStorage saving operations" should {
    val iri     = iri"http://localhost/disk"
    val project = ProjectRef.unsafe("org", "project")
    val value   = DiskStorageValue(default = true, DigestAlgorithm.default, volume, read, write, Some(100), 10)
    val storage = DiskStorage(iri, project, value, Tags.empty, Json.obj())
    val uuid    = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
    val content = "file content"
    val entity  = HttpEntity(content)

    "save a file to a volume" in {
      val description = FileDescription(uuid, "myfile.txt", Some(`text/plain(UTF-8)`))

      val attributes = storage.saveFile.apply(description, entity).accepted

      Files.readString(file.value) shouldEqual content

      attributes shouldEqual
        FileAttributes(
          uuid,
          s"file://$file",
          Uri.Path("org/project/8/0/4/9/b/a/9/0/myfile.txt"),
          "myfile.txt",
          Some(`text/plain(UTF-8)`),
          Files.size(file.value),
          ComputedDigest(DigestAlgorithm.default, RemoteStorageDocker.Digest),
          Client
        )

      consume(storage.fetchFile(attributes).accepted) shouldEqual content

    }

    "fail attempting to save the same file again" in {
      val description = FileDescription(uuid, "myfile.txt", Some(`text/plain(UTF-8)`))
      storage.saveFile.apply(description, entity).rejectedWith[ResourceAlreadyExists]
    }
  }

  override protected def afterAll(): Unit = {
    Directory(volume.value.toFile).deleteRecursively()
    ()
  }
}
