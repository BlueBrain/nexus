package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

/**
  * Information about the deployed service
  *
  * @param name    service name
  * @param version service version
  */
final private[client] case class RemoteDiskStorageServiceDescription(name: String, version: String)

object RemoteDiskStorageServiceDescription {
  implicit val serviceDescDecoder: Decoder[RemoteDiskStorageServiceDescription] =
    deriveDecoder[RemoteDiskStorageServiceDescription]
}
