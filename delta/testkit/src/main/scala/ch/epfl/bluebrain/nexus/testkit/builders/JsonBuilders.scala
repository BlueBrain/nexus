package ch.epfl.bluebrain.nexus.testkit.builders

import io.circe.syntax.KeyOps
import io.circe.{Encoder, Json}

object JsonBuilders {

  def optionalField[A: Encoder](name: String, value: Option[A]): Option[(String, Json)] = {
    value.map(name := _)
  }

  def optionalField[A: Encoder](name: String, values: List[A]): Option[(String, Json)] = {
    Option.when(values.nonEmpty)(name := values)
  }

  def optionalField[A: Encoder](name: String, values: Map[String, A]): Option[(String, Json)] = {
    Option.when(values.nonEmpty)(name := values)
  }
}
