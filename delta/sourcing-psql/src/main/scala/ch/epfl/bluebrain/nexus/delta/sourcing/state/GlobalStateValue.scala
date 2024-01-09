package ch.epfl.bluebrain.nexus.delta.sourcing.state

import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import doobie._
import io.circe.Decoder

import scala.annotation.nowarn

final case class GlobalStateValue[Value](
    offset: Offset,
    value: Value
)

object GlobalStateValue {
  @nowarn("cat=unused")
  implicit def envelopeRead[Value](implicit s: Decoder[Value]): Read[GlobalStateValue[Value]] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
    implicit val v: Get[Value] = pgDecoderGetT[Value]
    Read[(Value, Long)].map { case (value, offset) => GlobalStateValue(Offset.at(offset), value) }
  }
}
