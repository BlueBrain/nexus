package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.remotestorage.RemoteStorageDocker
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}

@DoNotDiscover
class RemoteDiskStorageAccessSpec(docker: RemoteStorageDocker)
    extends TestKit(ActorSystem("RemoteDiskStorageAccessSpec"))
    with CatsEffectSpec
    with TestHelpers
    with Eventually
    with StorageFixtures
    with BeforeAndAfterAll
    with ConfigFixtures {

  implicit private val httpConfig: HttpClientConfig = httpClientConfig
  private val httpClient: HttpClient                = HttpClient()
  private val authTokenProvider: AuthTokenProvider  = AuthTokenProvider.anonymousForTest
  private val remoteDiskStorageClient               =
    new RemoteDiskStorageClient(httpClient, authTokenProvider, Credentials.Anonymous)

  private val access = new RemoteDiskStorageAccess(remoteDiskStorageClient)

  private var storageValue: RemoteDiskStorageValue = _

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
  }

  "A RemoteDiskStorage access operations" should {
    val iri = iri"http://localhost/remote-disk"

    "succeed verifying the folder" in eventually {

      access(iri, storageValue).accepted
    }

    "fail when folder does not exist" in {
      val wrongFolder = storageValue.copy(folder = Label.unsafe("abcd"))
      access(iri, wrongFolder).rejectedWith[StorageNotAccessible]
    }
  }

}
