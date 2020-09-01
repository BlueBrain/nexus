package ch.epfl.bluebrain.nexus.sourcing.projections

import io.circe.{Decoder, Encoder}

final case class SomeEvent(rev: Long, description: String)

object SomeEvent {
  import io.circe.generic.semiauto._
  implicit final val encoder: Encoder[SomeEvent] = deriveEncoder[SomeEvent]
  implicit final val decoder: Decoder[SomeEvent] = deriveDecoder[SomeEvent]
}
