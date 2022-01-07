package ch.epfl.bluebrain.nexus.delta.sourcing2.decoder

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoderError.{ParsingError, UnexpectedEntityType, UnknownEntityType}
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.EntityType
import io.circe.{Decoder, Json}

/**
  * Allows to decode a json payload according to the entity type
  */
sealed trait PayloadDecoder[A] {

  def apply(tpe: EntityType, payload: Json): Either[PayloadDecoderError, A]

}

object PayloadDecoder {

  /**
    * Allows to decode the payload for a single entity type
    * @param tpe
    *   the entity type
    */
  final class SingleDecoder[A: Decoder](val tpe: EntityType) extends PayloadDecoder[A] {

    override def apply(tpe: EntityType, payload: Json): Either[PayloadDecoderError, A] =
      Either.cond(tpe == tpe, payload, UnexpectedEntityType(tpe, tpe)).flatMap {
        _.as[A].leftMap(ParsingError(tpe, _))
      }
  }

  /**
    * Allow to decode payload for multiple entity types
    */
  final class MultiDecoder[A](underlying: Set[SingleDecoder[A]]) extends PayloadDecoder[A] {

    private val decodersMap: Map[EntityType, SingleDecoder[A]] =
      underlying.foldLeft(Map.empty[EntityType, SingleDecoder[A]]) { (map, decoder) =>
        map.updated(decoder.tpe, decoder)
      }

    override def apply(tpe: EntityType, payload: Json): Either[PayloadDecoderError, A] =
      Either
        .fromOption(decodersMap.get(tpe), UnknownEntityType(tpe))
        .flatMap(_.apply(tpe, payload))

  }

  /**
    * Construct a [[SingleDecoder]]
    * @param tpe
    *   the entity type to decode
    */
  def apply[A: Decoder](tpe: EntityType): SingleDecoder[A] = new SingleDecoder[A](tpe)

  /**
    * Construct a [[MultiDecoder]]
    * @param underlying
    *   the specific decoders to rely on
    */
  def apply[A](underlying: Set[SingleDecoder[A]]) = new MultiDecoder[A](underlying)

}
