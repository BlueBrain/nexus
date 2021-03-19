package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder

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
}
