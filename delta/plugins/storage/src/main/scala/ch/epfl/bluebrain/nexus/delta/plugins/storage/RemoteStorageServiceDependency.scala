package ch.epfl.bluebrain.nexus.delta.plugins.storage

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.ServiceDependency
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription

/**
  * Describes the remote storage [[ServiceDependency]] providing a way to extract the [[ServiceDescription]] from a
  * remote storage calling its ''/version'' endpoint
  */
class RemoteStorageServiceDependency(remoteClient: RemoteDiskStorageClient, baseUri: BaseUri)
    extends ServiceDependency {

  override def serviceDescription: IO[ServiceDescription] =
    remoteClient.serviceDescription(baseUri)
}
