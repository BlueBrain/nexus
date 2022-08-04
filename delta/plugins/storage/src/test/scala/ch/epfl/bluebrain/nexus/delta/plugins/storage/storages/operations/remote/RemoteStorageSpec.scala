package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteStorageClientSpec
import ch.epfl.bluebrain.nexus.testkit.remotestorage.RemoteStorageDocker
import org.scalatest.Suite

class RemoteStorageSpec extends Suite with RemoteStorageDocker {
  override def nestedSuites: IndexedSeq[Suite] = Vector(
    new RemoteStorageClientSpec(this),
    new RemoteDiskStorageAccessSpec(this),
    new RemoteStorageSaveAndFetchFileSpec(this),
    new RemoteStorageLinkFileSpec(this) //,
    // TODO uncomment after migrating files
    //new FilesSpec(this)
  )
}
