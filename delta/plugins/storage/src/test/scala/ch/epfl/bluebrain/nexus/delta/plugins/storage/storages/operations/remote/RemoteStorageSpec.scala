package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteStorageClientSpec
import com.whisk.docker.scalatest.DockerTestKit
import org.scalatest.Suites

class RemoteStorageSpec
    extends Suites(new RemoteStorageClientSpec, new RemoteDiskStorageAccessSpec, new RemoteStorageSaveAndFetchFileSpec)
    with DockerTestKit
    with RemoteStorageDocker
