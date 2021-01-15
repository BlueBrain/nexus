package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection.FileAlreadyExists
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteStorageDocker.{digest, BucketName, RemoteStorageServicePort}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.Json
import monix.execution.Scheduler
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

@DoNotDiscover
class RemoteStorageSaveAndFetchFileSpec
    extends TestKit(ActorSystem("RemoteStorageSaveAndFetchFileSpec"))
    with AnyWordSpecLike
    with AkkaSourceHelpers
    with Matchers
    with IOValues
    with Eventually {

  implicit private val sc: Scheduler = Scheduler.global

  private val storageValue = RemoteDiskStorageValue(
    default = true,
    BaseUri(s"http://localhost:$RemoteStorageServicePort", Label.unsafe("v1")),
    None,
    BucketName,
    read,
    write,
    10
  )

  "RemoteDiskStorage operations" should {
    val iri = iri"http://localhost/remote"

    val uuid     = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
    val project  = ProjectRef.unsafe("org", "project")
    val filename = "myfile.txt"

    val storage = RemoteDiskStorage(iri, project, storageValue, Map.empty, Secret(Json.obj()))
    val content = "file content"
    val source  = Source(content.map(c => ByteString(c.toString)))

    val attributes = FileAttributes(
      uuid,
      s"file:///app/$BucketName/nexus/org/project/8/0/4/9/b/a/9/0/myfile.txt",
      Uri.Path("org/project/8/0/4/9/b/a/9/0/myfile.txt"),
      "myfile.txt",
      `text/plain(UTF-8)`,
      12,
      digest,
      Client
    )

    "save a file to a folder" in {
      val description = FileDescription(uuid, filename, Some(`text/plain(UTF-8)`))
      storage.saveFile(description, source).accepted shouldEqual attributes
    }

    "fetch a file from a folder" in {
      val sourceFetched = storage.fetchFile(attributes).accepted
      consume(sourceFetched) shouldEqual content
    }

    "fetch a file attributes" in eventually {
      val computedAttributes = storage.fetchComputedAttributes(attributes).accepted
      computedAttributes.digest shouldEqual attributes.digest
      computedAttributes.bytes shouldEqual attributes.bytes
      computedAttributes.mediaType shouldEqual attributes.mediaType
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
