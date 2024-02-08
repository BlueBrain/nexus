package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.FilesSpec
import ch.epfl.bluebrain.nexus.delta.plugins.storage.remotestorage.RemoteStorageClientFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteStorageClientSpec
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec
import org.scalatest.Suite

class RemoteStorageSpec extends BaseSpec with RemoteStorageClientFixtures {
  override def nestedSuites: IndexedSeq[Suite] = Vector(
    new RemoteStorageClientSpec(this),
    new RemoteDiskStorageAccessSpec(this),
    new RemoteStorageSaveAndFetchFileSpec(this),
    new RemoteStorageLinkFileSpec(this),
    new FilesSpec(this)
  )
}
