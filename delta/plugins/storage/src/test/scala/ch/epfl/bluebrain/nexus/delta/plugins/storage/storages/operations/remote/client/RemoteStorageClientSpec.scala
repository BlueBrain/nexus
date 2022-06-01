package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{HttpEntity, StatusCodes, Uri}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.{ComputedDigest, NotComputedDigest}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.{FetchFileRejection, MoveFileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.remotestorage.RemoteStorageDocker
import ch.epfl.bluebrain.nexus.testkit.remotestorage.RemoteStorageDocker.BucketName
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues}
import monix.execution.Scheduler
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}

import scala.concurrent.ExecutionContext

@DoNotDiscover
class RemoteStorageClientSpec(docker: RemoteStorageDocker)
    extends TestKit(ActorSystem("RemoteStorageClientSpec"))
    with AnyWordSpecLike
    with Matchers
    with IOValues
    with AkkaSourceHelpers
    with Eventually
    with BeforeAndAfterAll
    with EitherValuable
    with ConfigFixtures {

  implicit val ec: ExecutionContext = system.dispatcher

  private var client: RemoteDiskStorageClient = _
  private val bucket: Label                   = Label.unsafe(BucketName)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val httpConfig: HttpClientConfig    = httpClientConfig
    implicit val httpClient: HttpClient = HttpClient()(httpConfig, system, Scheduler.global)
    client = new RemoteDiskStorageClient(BaseUri(docker.hostConfig.endpoint).rightValue)
  }

  "A RemoteStorage client" should {

    implicit val cred: Option[AuthToken] = None
    val content                          = RemoteStorageDocker.Content
    val entity                           = HttpEntity(content)
    val attributes                       = RemoteDiskStorageFileAttributes(
      location = s"file:///app/$BucketName/nexus/my/file.txt",
      bytes = 12,
      digest = ComputedDigest(DigestAlgorithm.default, RemoteStorageDocker.Digest),
      mediaType = `text/plain(UTF-8)`
    )

    "fetch the service description" in eventually {
      client.serviceDescription.accepted shouldEqual ServiceDescription(Name.unsafe("remoteStorage"), "1.7.0")
    }

    "check if a bucket exists" in {
      client.exists(bucket).accepted
      val error = client.exists(Label.unsafe("other")).rejectedWith[HttpClientStatusError]
      error.code == StatusCodes.NotFound
    }

    "create a file" in {
      client.createFile(bucket, Uri.Path("my/file.txt"), entity).accepted shouldEqual attributes
    }

    "get a file" in {
      consume(client.getFile(bucket, Uri.Path("my/file.txt")).accepted) shouldEqual content
    }

    "fail to get a file that does not exist" in {
      client.getFile(bucket, Uri.Path("my/file3.txt")).rejectedWith[FetchFileRejection.FileNotFound]
    }

    "get a file attributes" in eventually {
      client.getAttributes(bucket, Uri.Path("my/file.txt")).accepted shouldEqual attributes
    }

    "move a file" in {
      client.moveFile(bucket, Uri.Path("my/file-1.txt"), Uri.Path("other/file-1.txt")).accepted shouldEqual
        attributes.copy(
          location = s"file:///app/$BucketName/nexus/other/file-1.txt",
          digest = NotComputedDigest
        )
    }

    "fail to move a file that does not exist" in {
      client
        .moveFile(bucket, Uri.Path("my/file.txt"), Uri.Path("other/file.txt"))
        .rejectedWith[MoveFileRejection.FileNotFound]

    }
  }
}
