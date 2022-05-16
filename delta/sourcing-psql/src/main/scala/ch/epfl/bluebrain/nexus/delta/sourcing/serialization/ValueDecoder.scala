package ch.epfl.bluebrain.nexus.delta.sourcing.serialization

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.serialization.ValueDecodingError.{ParsingError, UnexpectedEntityType, UnknownEntityType}
import doobie.Get
import io.circe.{Decoder, Json}

sealed trait ValueDecoder[Id, Value] {

  implicit def getId: Get[Id]

  def apply(entityType: EntityType, json: Json): Either[ValueDecodingError, Value]

}

object ValueDecoder {

  /**
    * Allows to decode the payload for a single entity type
    * @param tpe
    *   the entity type
    */
  final case class SingleDecoder[Id, Value](tpe: EntityType)(implicit override val getId: Get[Id], decoder: Decoder[Value]) extends ValueDecoder[Id, Value] {

    override def apply(tpe: EntityType, json: Json): Either[ValueDecodingError, Value] =
      Either.cond(tpe == tpe, json, UnexpectedEntityType(tpe, tpe)).flatMap {
        _.as[Value].leftMap(ParsingError(tpe, _))
      }
  }

  /**
    * Allow to decode payload for multiple entity types
    */
  final case class MultiDecoder[Id, Value](underlying: Set[SingleDecoder[_, Value]])(implicit override val getId: Get[Id]) extends ValueDecoder[Id, Value] {

    private val decodersMap: Map[EntityType, SingleDecoder[Id, Value]] =
      underlying.foldLeft(Map.empty[EntityType, SingleDecoder[Id, Value]]) { (map, decoder) =>
        map.updated(decoder.tpe, decoder)
      }

    override def apply(tpe: EntityType, json: Json): Either[ValueDecodingError, Value] =
      Either
        .fromOption(decodersMap.get(tpe), UnknownEntityType(tpe))
        .flatMap(_.apply(tpe, json))

  }

  /**
    * Construct a [[SingleDecoder]]
    * @param tpe
    *   the entity type to decode
    */
  def apply[Id: Get, Value: Decoder](tpe: EntityType): SingleDecoder[Id, Value] = new SingleDecoder[Id, Value](tpe)

  /**
    * Construct a [[MultiDecoder]]
    * @param underlying
    *   the specific decoders to rely on
    */
  def apply[Id: Get, Value](underlying: Set[SingleDecoder[_, Value]]) = new MultiDecoder[Id, Value](underlying)

}
