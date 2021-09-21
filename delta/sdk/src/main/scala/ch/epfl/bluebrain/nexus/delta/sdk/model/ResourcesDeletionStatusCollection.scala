package ch.epfl.bluebrain.nexus.delta.sdk.model

import io.circe.{Decoder, Encoder}

import java.util.UUID

/**
  * The collection of ResourcesDeletionStatus
  */
final case class ResourcesDeletionStatusCollection(value: Map[UUID, ResourcesDeletionStatus]) {
  def +(tuple: (UUID, ResourcesDeletionStatus)): ResourcesDeletionStatusCollection =
    ResourcesDeletionStatusCollection(value + tuple)
}

object ResourcesDeletionStatusCollection {

  val empty: ResourcesDeletionStatusCollection = ResourcesDeletionStatusCollection(Map.empty)

  implicit def resourcesDeletionStatusColEncoder(implicit base: BaseUri): Encoder[ResourcesDeletionStatusCollection] =
    Encoder.encodeMap[UUID, ResourcesDeletionStatus].contramap(_.value)

  implicit def resourcesDeletionStatusColDecoder(implicit base: BaseUri): Decoder[ResourcesDeletionStatusCollection] =
    Decoder.decodeMap[UUID, ResourcesDeletionStatus].map(ResourcesDeletionStatusCollection(_))
}
