package ch.epfl.bluebrain.nexus.delta.sourcing

import io.circe.Codec
import io.circe.generic.extras.Configuration

/**
  * Defines how to extract an id from an event/state and how to serialize and deserialize it
  * @param extractId
  *   to extract an identifier from an event
  * @param codec
  *   the Circe codec to serialize/deserialize the event/state from the database
  */
final class Serializer[Id, Value] private (val extractId: Value => Id)(implicit val codec: Codec.AsObject[Value])

object Serializer {
  val circeConfiguration: Configuration = Configuration.default.withDiscriminator("@type")

  def apply[Id, Value](extractId: Value => Id)(implicit codec: Codec.AsObject[Value]): Serializer[Id, Value] =
    new Serializer(extractId)

}
