package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.{HttpEntity, Uri}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
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
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
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

  private val iri                   = iri"http://localhost/remote"
  private val uuid                  = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
  implicit private val uuidf: UUIDF = UUIDF.fixed(uuid)
  private val project               = ProjectRef.unsafe("org", "project")
  private val filename              = "myfile.txt"

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
    storage = RemoteDiskStorage(iri, project, storageValue, Json.obj())
  }

  "RemoteDiskStorage operations" should {
    val content = "file content"
    val entity  = HttpEntity(content)

    val bytes    = 12L
    val digest   = ComputedDigest(DigestAlgorithm.default, RemoteStorageDocker.Digest)
    val location = s"file:///app/${RemoteStorageDocker.BucketName}/nexus/org/project/8/0/4/9/b/a/9/0/myfile.txt"
    val path     = Uri.Path("org/project/8/0/4/9/b/a/9/0/myfile.txt")

    "save a file to a folder" in {
      storage.saveFile(remoteDiskStorageClient).apply(filename, entity).accepted shouldEqual FileStorageMetadata(
        uuid,
        bytes,
        digest,
        Client,
        location,
        path
      )
    }

    "fetch a file from a folder" in {
      val sourceFetched = storage.fetchFile(remoteDiskStorageClient).apply(path).accepted
      consume(sourceFetched) shouldEqual content
    }

    "fetch a file attributes" in eventually {
      val computedAttributes = storage.fetchComputedAttributes(remoteDiskStorageClient).apply(path).accepted
      computedAttributes.digest shouldEqual digest
      computedAttributes.bytes shouldEqual bytes
      computedAttributes.mediaType shouldEqual `text/plain(UTF-8)`
    }

    "fail fetching a file that does not exist" in {
      storage
        .fetchFile(remoteDiskStorageClient)
        .apply(Uri.Path("other.txt"))
        .rejectedWith[FileNotFound]
    }

    "fail attempting to save the same file again" in {
      storage.saveFile(remoteDiskStorageClient).apply(filename, entity).rejectedWith[ResourceAlreadyExists]
    }
  }
}
