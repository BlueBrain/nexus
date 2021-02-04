package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import monix.bio.UIO

/**
  * Describes the remote storage [[ServiceDependency]] providing a way to
  * extract the [[ServiceDescription]] from a remote storage calling its ''/version'' endpoint
  */
class RemoteStorageServiceDependency(remoteClient: RemoteDiskStorageClient) extends ServiceDependency {

  override def serviceDescription: UIO[ServiceDescription] =
    remoteClient.serviceDescription
}
