package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.NotComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteStorageDocker.{BucketName, RemoteStorageEndpoint}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.Json
import monix.execution.Scheduler
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

@DoNotDiscover
class RemoteStorageMoveFileSpec
    extends TestKit(ActorSystem("RemoteStorageMoveFileSpec"))
    with AnyWordSpecLike
    with AkkaSourceHelpers
    with Matchers
    with IOValues {

  implicit private val sc: Scheduler = Scheduler.global

  private val storageValue =
    RemoteDiskStorageValue(default = true, RemoteStorageEndpoint, None, BucketName, read, write, 10)

  "RemoteDiskStorage moving operations" should {
    val iri = iri"http://localhost/remote"

    val uuid     = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
    val project  = ProjectRef.unsafe("org", "project")
    val filename = "file-2.txt"

    val storage = RemoteDiskStorage(iri, project, storageValue, Map.empty, Secret(Json.obj()))

    val attributes  = FileAttributes(
      uuid,
      s"file:///app/$BucketName/nexus/org/project/8/0/4/9/b/a/9/0/file-2.txt",
      Uri.Path("org/project/8/0/4/9/b/a/9/0/file-2.txt"),
      "file-2.txt",
      `text/plain(UTF-8)`,
      12,
      NotComputedDigest,
      Storage
    )
    val description = FileDescription(uuid, filename, Some(`text/plain(UTF-8)`))

    "succeed" in {
      storage.moveFile(Uri.Path("my/file-2.txt"), description).accepted shouldEqual
        attributes
    }

    "fail moving a file that does not exist" in {
      storage.moveFile(Uri.Path("my/file-40.txt"), description).rejectedWith[FileNotFound]
    }
  }
}
