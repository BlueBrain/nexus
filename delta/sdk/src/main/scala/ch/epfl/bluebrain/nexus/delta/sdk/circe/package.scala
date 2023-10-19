package ch.epfl.bluebrain.nexus.delta.sdk

import cats.Order
import cats.data.NonEmptyMap
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, JsonObject}

package object circe {

  object nonEmptyMap {
    implicit def dropKeyEncoder[K, V](implicit encodeV: Encoder[V]): Encoder[NonEmptyMap[K, V]] =
      Encoder.instance { map =>
        map.toNel.map(_._2).asJson
      }

    def dropKeyDecoder[K, V](
        extract: V => K
    )(implicit orderK: Order[K], decodeV: Decoder[V]): Decoder[NonEmptyMap[K, V]] =
      Decoder.decodeNonEmptyList[V].map {
        _.map { v => extract(v) -> v }.toNem
      }

  }

  implicit class JsonObjOps(j: JsonObject) {
    def dropNulls: JsonObject = dropNullValues(j)
  }

  def dropNullValues(j: JsonObject): JsonObject = j.filter { case (_, v) => !v.isNull }
}
