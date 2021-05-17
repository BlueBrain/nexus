package ch.epfl.bluebrain.nexus.delta.sdk

import io.circe.Encoder

/**
  * A definition of a value that can be converted to Json
  */
sealed trait JsonValue {
  type A
  def value: A
  def encoder: Encoder.AsObject[A]
}

object JsonValue {

  type Aux[A0] = JsonValue { type A = A0 }

  /**
    * Constructs a [[JsonValue]] form a value and its [[Encoder]]
    */
  def apply[A0: Encoder.AsObject](v: A0): JsonValue.Aux[A0] =
    new JsonValue {
      override type A = A0
      override val value: A                     = v
      override val encoder: Encoder.AsObject[A] = implicitly[Encoder.AsObject[A]]
    }
}
