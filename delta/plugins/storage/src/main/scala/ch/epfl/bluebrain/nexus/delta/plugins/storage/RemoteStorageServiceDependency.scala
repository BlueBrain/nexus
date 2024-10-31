package ch.epfl.bluebrain.nexus.delta.plugins.storage

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient

/**
  * Describes the remote storage [[ServiceDependency]] providing a way to extract the [[ServiceDescription]] from a
  * remote storage calling its ''/version'' endpoint
  */
class RemoteStorageServiceDependency(remoteClient: RemoteDiskStorageClient) extends ServiceDependency {

  override def serviceDescription: IO[ServiceDescription] = remoteClient.serviceDescription
}
