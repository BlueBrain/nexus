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
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Name}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.remotestorage.RemoteStorageDocker
import ch.epfl.bluebrain.nexus.testkit.remotestorage.RemoteStorageDocker.BucketName
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}

@DoNotDiscover
class RemoteStorageClientSpec(docker: RemoteStorageDocker)
    extends TestKit(ActorSystem("RemoteStorageClientSpec"))
    with CatsEffectSpec
    with AkkaSourceHelpers
    with Eventually
    with BeforeAndAfterAll
    with ConfigFixtures {

  private var client: RemoteDiskStorageClient      = _
  private var baseUri: BaseUri                     = _
  private val authTokenProvider: AuthTokenProvider = AuthTokenProvider.anonymousForTest
  private val bucket: Label                        = Label.unsafe(BucketName)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    implicit val httpConfig: HttpClientConfig = httpClientConfig
    implicit val httpClient: HttpClient       = HttpClient()

    client = new RemoteDiskStorageClient(httpClient, authTokenProvider, Credentials.Anonymous)
    baseUri = BaseUri(docker.hostConfig.endpoint).rightValue
  }

  "A RemoteStorage client" should {

    val content    = RemoteStorageDocker.Content
    val entity     = HttpEntity(content)
    val attributes = RemoteDiskStorageFileAttributes(
      location = s"file:///app/$BucketName/nexus/my/file.txt",
      bytes = 12,
      digest = ComputedDigest(DigestAlgorithm.default, RemoteStorageDocker.Digest),
      mediaType = `text/plain(UTF-8)`
    )

    "fetch the service description" in eventually {
      client.serviceDescription(baseUri).accepted shouldEqual ServiceDescription(
        Name.unsafe("remoteStorage"),
        docker.storageVersion
      )
    }

    "check if a bucket exists" in {
      client.exists(bucket)(baseUri).accepted
      val error = client.exists(Label.unsafe("other"))(baseUri).rejectedWith[HttpClientStatusError]
      error.code == StatusCodes.NotFound
    }

    "create a file" in {
      client.createFile(bucket, Uri.Path("my/file.txt"), entity)(baseUri).accepted shouldEqual attributes
    }

    "get a file" in {
      consume(client.getFile(bucket, Uri.Path("my/file.txt"))(baseUri).accepted) shouldEqual content
    }

    "fail to get a file that does not exist" in {
      client.getFile(bucket, Uri.Path("my/file3.txt"))(baseUri).rejectedWith[FetchFileRejection.FileNotFound]
    }

    "get a file attributes" in eventually {
      client.getAttributes(bucket, Uri.Path("my/file.txt"))(baseUri).accepted shouldEqual attributes
    }

    "move a file" in {
      client.moveFile(bucket, Uri.Path("my/file-1.txt"), Uri.Path("other/file-1.txt"))(baseUri).accepted shouldEqual
        attributes.copy(
          location = s"file:///app/$BucketName/nexus/other/file-1.txt",
          digest = NotComputedDigest
        )
    }

    "fail to move a file that does not exist" in {
      client
        .moveFile(bucket, Uri.Path("my/file.txt"), Uri.Path("other/file.txt"))(baseUri)
        .rejectedWith[MoveFileRejection.FileNotFound]

    }
  }
}
