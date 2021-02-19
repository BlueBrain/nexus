package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import io.circe.{Decoder, Encoder}

final case class SomeEvent(rev: Long, description: String)

object SomeEvent {

  val empty: SomeEvent = SomeEvent(0, "")

  import io.circe.generic.semiauto._
  implicit final val encoder: Encoder[SomeEvent] = deriveEncoder[SomeEvent]
  implicit final val decoder: Decoder[SomeEvent] = deriveDecoder[SomeEvent]
}
