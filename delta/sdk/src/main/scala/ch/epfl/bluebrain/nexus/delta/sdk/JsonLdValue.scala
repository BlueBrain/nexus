package ch.epfl.bluebrain.nexus.delta.sdk

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}

/**
  * A definition of a value that can be converted to JSONLD
  */
sealed trait JsonLdValue {
  type A
  def value: A
  def encoder: JsonLdEncoder[A]
}

object JsonLdValue {

  type Aux[A0] = JsonLdValue { type A = A0 }

  /**
    * Constructs a [[JsonLdValue]] form a value and its [[JsonLdEncoder]]
    */
  def apply[A0: JsonLdEncoder](v: A0): JsonLdValue.Aux[A0] =
    new JsonLdValue {
      override type A = A0
      override val value: A                  = v
      override val encoder: JsonLdEncoder[A] = implicitly[JsonLdEncoder[A]]
    }

  implicit val jsonLdEncoder: JsonLdEncoder[JsonLdValue] = {
    new JsonLdEncoder[JsonLdValue] {
      override def context(value: JsonLdValue): ContextValue = value.encoder.context(value.value)
      override def expand(
          value: JsonLdValue
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[ExpandedJsonLd] =
        value.encoder.expand(value.value)
      override def compact(
          value: JsonLdValue
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[CompactedJsonLd] =
        value.encoder.compact(value.value)
    }
  }
}
