package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.IriInstances._
import doobie.Put
import io.circe.Codec
import io.circe.generic.extras.Configuration

/**
  * Defines how to extract an id from an event/state and how to serialize and deserialize it
  * @param encodeId
  *   to encode the identifier as an Iri
  * @param codec
  *   the Circe codec to serialize/deserialize the event/state from the database
  */
final class Serializer[Id, Value] private (val encodeId: Id => Iri)(implicit val codec: Codec.AsObject[Value]) {

  implicit val put: Put[Id] = Put[Iri].contramap(encodeId)

}

object Serializer {
  val circeConfiguration: Configuration = Configuration.default.withDiscriminator("@type")

  def apply[Id, Value](extractId: Id => Iri)(implicit codec: Codec.AsObject[Value]): Serializer[Id, Value] =
    new Serializer(extractId)

  def apply[Value]()(implicit codec: Codec.AsObject[Value]): Serializer[Iri, Value] =
    new Serializer(identity)

}
