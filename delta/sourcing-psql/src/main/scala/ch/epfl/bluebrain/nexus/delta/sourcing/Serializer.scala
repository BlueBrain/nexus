package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import doobie.{Get, Put}
import io.circe.generic.extras.Configuration
import io.circe.{Codec, Printer}

/**
  * Defines how to extract an id from an event/state and how to serialize and deserialize it
  * @param encodeId
  *   to encode the identifier as an Iri
  * @param codec
  *   the Circe codec to serialize/deserialize the event/state from the database
  */
final class Serializer[Id, Value] private (
    encodeId: Id => Iri,
    val codec: Codec.AsObject[Value],
    val printer: Printer
) {

  def putId: Put[Id] = Put[Iri].contramap(encodeId)

  def getValue: Get[Value] = jsonbGet.temap(v => codec.decodeJson(v).leftMap(_.message))

  def putValue: Put[Value] = jsonbPut(printer).contramap(codec(_))
}

object Serializer {
  val circeConfiguration: Configuration = Configuration.default.withDiscriminator("@type")

  private val defaultPrinter: Printer = Printer.noSpaces

  private val dropNullsPrinter: Printer = Printer.noSpaces.copy(dropNullValues = true)

  /**
    * Defines a serializer with the default printer serializing null values with a custom [[Id]] type
    */
  def apply[Id, Value](extractId: Id => Iri)(implicit codec: Codec.AsObject[Value]): Serializer[Id, Value] =
    new Serializer(extractId, codec, defaultPrinter)

  /**
    * Defines a serializer with the default printer serializing null values with an [[Iri]] id
    */
  def apply[Value]()(implicit codec: Codec.AsObject[Value]): Serializer[Iri, Value] =
    apply(identity[Iri])(codec)

  /**
    * Defines a serializer with the default printer ignoring null values with a custom [[Id]] type
    */
  def dropNulls[Id, Value](extractId: Id => Iri)(implicit codec: Codec.AsObject[Value]): Serializer[Id, Value] =
    new Serializer(extractId, codec, dropNullsPrinter)

  /**
    * Defines a serializer with the default printer ignoring null values with an [[Iri]] id
    */
  def dropNulls[Value]()(implicit codec: Codec.AsObject[Value]): Serializer[Iri, Value] =
    dropNulls(identity[Iri])(codec)

}
