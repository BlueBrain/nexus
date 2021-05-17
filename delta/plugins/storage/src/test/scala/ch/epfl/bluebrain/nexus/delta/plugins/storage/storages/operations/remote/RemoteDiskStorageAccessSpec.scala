package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteStorageDocker._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

@DoNotDiscover
class RemoteDiskStorageAccessSpec
    extends TestKit(ActorSystem("RemoteDiskStorageAccessSpec"))
    with AnyWordSpecLike
    with Matchers
    with IOValues
    with TestHelpers
    with Eventually
    with StorageFixtures
    with ConfigFixtures {
  implicit private val sc: Scheduler                = Scheduler.global
  implicit private val httpConfig: HttpClientConfig = httpClientConfig
  implicit private val httpClient: HttpClient       = HttpClient()

  private val access = new RemoteDiskStorageAccess

  "A RemoteDiskStorage access operations" should {
    val iri = iri"http://localhost/remote-disk"

    val value = RemoteDiskStorageValue(
      default = true,
      DigestAlgorithm.default,
      RemoteStorageEndpoint,
      None,
      BucketName,
      read,
      write,
      10
    )

    "succeed verifying the folder" in eventually {

      access(iri, value).accepted
    }

    "fail when folder does not exist" in {
      val wrongFolder = value.copy(folder = Label.unsafe("abcd"))
      access(iri, wrongFolder).rejectedWith[StorageNotAccessible]
    }
  }

}
