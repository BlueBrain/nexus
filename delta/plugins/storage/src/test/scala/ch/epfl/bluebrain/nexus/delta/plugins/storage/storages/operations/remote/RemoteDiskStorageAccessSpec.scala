package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.remotestorage.RemoteStorageClientFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}

@DoNotDiscover
class RemoteDiskStorageAccessSpec(fixture: RemoteStorageClientFixtures)
    extends TestKit(ActorSystem("RemoteDiskStorageAccessSpec"))
    with CatsEffectSpec
    with Eventually
    with StorageFixtures
    with BeforeAndAfterAll
    with RemoteStorageClientFixtures {

  private lazy val remoteDiskStorageClient = fixture.init

  private lazy val access = new RemoteDiskStorageAccess(remoteDiskStorageClient)

  private val storageValue: RemoteDiskStorageValue = RemoteDiskStorageValue(
    default = true,
    DigestAlgorithm.default,
    Label.unsafe(RemoteStorageClientFixtures.BucketName),
    read,
    write,
    10
  )

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
