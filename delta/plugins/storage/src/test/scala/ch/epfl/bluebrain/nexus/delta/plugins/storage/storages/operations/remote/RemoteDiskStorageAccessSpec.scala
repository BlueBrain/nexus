package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.remotestorage.RemoteStorageClientFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageFixtures, UUIDFFixtures}
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
    with UUIDFFixtures.Random
    with BeforeAndAfterAll
    with RemoteStorageClientFixtures {

  private lazy val remoteDiskStorageClient = fixture.init
  private lazy val fileOps                 = RemoteDiskFileOperations.mk(remoteDiskStorageClient)

  "A RemoteDiskStorage access operations" should {

    "succeed verifying the folder" in eventually {
      fileOps.checkFolderExists(Label.unsafe(RemoteStorageClientFixtures.BucketName)).accepted
    }

    "fail when folder does not exist" in {
      fileOps.checkFolderExists(Label.unsafe(genString())).rejectedWith[StorageNotAccessible]
    }
  }

}
