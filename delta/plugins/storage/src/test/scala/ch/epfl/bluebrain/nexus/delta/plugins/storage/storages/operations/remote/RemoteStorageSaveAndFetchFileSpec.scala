package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{HttpEntity, Uri}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection.ResourceAlreadyExists
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.remotestorage.RemoteStorageDocker
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}

import java.util.UUID
import scala.concurrent.ExecutionContext

@DoNotDiscover
class RemoteStorageSaveAndFetchFileSpec(docker: RemoteStorageDocker)
    extends TestKit(ActorSystem("RemoteStorageSaveAndFetchFileSpec"))
    with CatsEffectSpec
    with AkkaSourceHelpers
    with Eventually
    with BeforeAndAfterAll
    with StorageFixtures
    with ConfigFixtures {

  implicit val ec: ExecutionContext                 = system.dispatcher
  implicit private val httpConfig: HttpClientConfig = httpClientConfig
  private val httpClient: HttpClient                = HttpClient()
  private val authTokenProvider: AuthTokenProvider  = AuthTokenProvider.anonymousForTest
  private val remoteDiskStorageClient               =
    new RemoteDiskStorageClient(httpClient, authTokenProvider, Credentials.Anonymous)

  private val iri      = iri"http://localhost/remote"
  private val uuid     = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
  private val project  = ProjectRef.unsafe("org", "project")
  private val filename = "myfile.txt"

  private var storageValue: RemoteDiskStorageValue = _
  private var storage: RemoteDiskStorage           = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    storageValue = RemoteDiskStorageValue(
      default = true,
      DigestAlgorithm.default,
      BaseUri(docker.hostConfig.endpoint).rightValue,
      Label.unsafe(RemoteStorageDocker.BucketName),
      read,
      write,
      10
    )
    storage = RemoteDiskStorage(iri, project, storageValue, Tags.empty, Json.obj())
  }

  "RemoteDiskStorage operations" should {
    val content = "file content"
    val entity  = HttpEntity(content)

    val attributes = FileAttributes(
      uuid,
      s"file:///app/${RemoteStorageDocker.BucketName}/nexus/org/project/8/0/4/9/b/a/9/0/myfile.txt",
      Uri.Path("org/project/8/0/4/9/b/a/9/0/myfile.txt"),
      "myfile.txt",
      Some(`text/plain(UTF-8)`),
      12,
      ComputedDigest(DigestAlgorithm.default, RemoteStorageDocker.Digest),
      Client
    )

    "save a file to a folder" in {
      val description = FileDescription(uuid, filename, Some(`text/plain(UTF-8)`))
      storage.saveFile(remoteDiskStorageClient).apply(description, entity).accepted shouldEqual attributes
    }

    "fetch a file from a folder" in {
      val sourceFetched = storage.fetchFile(remoteDiskStorageClient).apply(attributes).accepted
      consume(sourceFetched) shouldEqual content
    }

    "fetch a file attributes" in eventually {
      val computedAttributes = storage.fetchComputedAttributes(remoteDiskStorageClient).apply(attributes).accepted
      computedAttributes.digest shouldEqual attributes.digest
      computedAttributes.bytes shouldEqual attributes.bytes
      computedAttributes.mediaType shouldEqual attributes.mediaType.value
    }

    "fail fetching a file that does not exist" in {
      storage
        .fetchFile(remoteDiskStorageClient)
        .apply(attributes.copy(path = Uri.Path("other.txt")))
        .rejectedWith[FileNotFound]
    }

    "fail attempting to save the same file again" in {
      val description = FileDescription(uuid, "myfile.txt", Some(`text/plain(UTF-8)`))
      storage.saveFile(remoteDiskStorageClient).apply(description, entity).rejectedWith[ResourceAlreadyExists]
    }
  }
}
