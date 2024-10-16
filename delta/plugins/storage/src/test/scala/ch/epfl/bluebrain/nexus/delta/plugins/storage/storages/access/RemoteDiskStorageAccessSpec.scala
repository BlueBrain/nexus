package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.access

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.storage.remotestorage.RemoteStorageClientFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually

@DoNotDiscover
class RemoteDiskStorageAccessSpec(fixture: RemoteStorageClientFixtures)
    extends TestKit(ActorSystem("RemoteDiskStorageAccessSpec"))
    with CatsEffectSpec
    with Eventually
    with RemoteStorageClientFixtures {

  private lazy val remoteDiskStorageClient = fixture.init
  private lazy val remoteAccess            = RemoteStorageAccess(remoteDiskStorageClient)

  "A RemoteDiskStorage access" should {

    "succeed verifying the folder" in eventually {
      remoteAccess.checkFolderExists(Label.unsafe(RemoteStorageClientFixtures.BucketName)).accepted
    }

    "fail when folder does not exist" in {
      remoteAccess.checkFolderExists(Label.unsafe(genString())).rejectedWith[StorageNotAccessible]
    }
  }

}
