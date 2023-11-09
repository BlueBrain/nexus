package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.Uri
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.NotComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection.FileNotFound
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
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}

import java.util.UUID
import scala.concurrent.ExecutionContext

@DoNotDiscover
class RemoteStorageLinkFileSpec(docker: RemoteStorageDocker)
    extends TestKit(ActorSystem("RemoteStorageMoveFileSpec"))
    with CatsEffectSpec
    with AkkaSourceHelpers
    with StorageFixtures
    with BeforeAndAfterAll
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
  private val filename = "file-2.txt"

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

  "RemoteDiskStorage linking operations" should {
    val attributes  = FileAttributes(
      uuid,
      s"file:///app/${RemoteStorageDocker.BucketName}/nexus/org/project/8/0/4/9/b/a/9/0/file-2.txt",
      Uri.Path("org/project/8/0/4/9/b/a/9/0/file-2.txt"),
      "file-2.txt",
      Some(`text/plain(UTF-8)`),
      12,
      NotComputedDigest,
      Storage
    )
    val description = FileDescription(uuid, filename, Some(`text/plain(UTF-8)`))

    "succeed" in {
      storage.linkFile(remoteDiskStorageClient).apply(Uri.Path("my/file-2.txt"), description).accepted shouldEqual
        attributes
    }

    "fail linking a file that does not exist" in {
      storage
        .linkFile(remoteDiskStorageClient)
        .apply(Uri.Path("my/file-40.txt"), description)
        .rejectedWith[FileNotFound]
    }
  }
}
