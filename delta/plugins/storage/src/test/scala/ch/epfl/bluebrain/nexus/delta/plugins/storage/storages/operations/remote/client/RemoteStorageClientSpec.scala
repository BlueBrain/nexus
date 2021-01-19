package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.NotComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.{FetchFileRejection, MoveFileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteStorageDocker._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.{RemoteDiskStorageFileAttributes, RemoteDiskStorageServiceDescription}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.execution.Scheduler
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

@DoNotDiscover
class RemoteStorageClientSpec
    extends TestKit(ActorSystem("RemoteStorageClientSpec"))
    with AnyWordSpecLike
    with Matchers
    with IOValues
    with AkkaSourceHelpers
    with Eventually {

  "A RemoteStorage client" should {

    implicit val sc: Scheduler           = Scheduler.global
    implicit val cred: Option[AuthToken] = None
    val content                          = "file content"
    val source                           = Source(content.map(c => ByteString(c.toString)))
    val attributes                       = RemoteDiskStorageFileAttributes(
      location = s"file:///app/$BucketName/nexus/my/file.txt",
      bytes = 12,
      digest = digest,
      mediaType = `text/plain(UTF-8)`
    )

    val client = new RemoteDiskStorageClient(HttpClient.apply, RemoteStorageEndpoint)

    "fetch the service description" in eventually {
      client.serviceDescription.accepted shouldEqual RemoteDiskStorageServiceDescription("storage", "1.4.1")
    }

    "check if a bucket exists" in {
      client.exists(BucketName).accepted
      val error = client.exists(Label.unsafe("other")).rejectedWith[HttpClientStatusError]
      error.code == StatusCodes.NotFound
    }

    "create a file" in {
      client.createFile(BucketName, Uri.Path("my/file.txt"), source).accepted shouldEqual attributes
    }

    "get a file" in {
      consume(client.getFile(BucketName, Uri.Path("my/file.txt")).accepted) shouldEqual content
    }

    "fail to get a file that does not exist" in {
      client.getFile(BucketName, Uri.Path("my/file3.txt")).rejectedWith[FetchFileRejection.FileNotFound]
    }

    "get a file attributes" in eventually {
      client.getAttributes(BucketName, Uri.Path("my/file.txt")).accepted shouldEqual attributes
    }

    "move a file" in {
      client.moveFile(BucketName, Uri.Path("my/file.txt"), Uri.Path("other/file.txt")).accepted shouldEqual
        attributes.copy(
          location = s"file:///app/$BucketName/nexus/other/file.txt",
          digest = NotComputedDigest
        )
    }

    "fail to move a file that does not exist" in {
      client
        .moveFile(BucketName, Uri.Path("my/file.txt"), Uri.Path("other/file.txt"))
        .rejectedWith[MoveFileRejection.FileNotFound]

    }
  }
}
