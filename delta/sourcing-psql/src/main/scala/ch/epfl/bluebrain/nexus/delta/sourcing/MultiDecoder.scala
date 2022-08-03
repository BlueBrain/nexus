package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import io.circe.{Decoder, DecodingFailure, Json}

final class MultiDecoder[Value] private (decoders: Map[EntityType, Decoder[Value]]) {

  def decodeJson(entityType: EntityType, json: Json): Either[DecodingFailure, Value] =
    decoders.get(entityType).toRight(DecodingFailure(s"No decoder is available for entity type $entityType", List.empty))
      .flatMap {_.decodeJson(json)
  }

}

object MultiDecoder {

  def apply[Value](decoders: Map[EntityType, Decoder[Value]]) = new MultiDecoder(decoders)

  def apply[Value](decoders: (EntityType, Decoder[Value])*): MultiDecoder[Value] = apply(decoders.toMap)

}
