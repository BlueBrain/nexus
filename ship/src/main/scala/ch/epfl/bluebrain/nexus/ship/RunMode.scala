package ch.epfl.bluebrain.nexus.ship

import io.circe.Encoder

sealed trait RunMode extends Product with Serializable

object RunMode {

  final case object Local extends RunMode

  final case object S3 extends RunMode

  implicit val runModeEncoder: Encoder[RunMode] = Encoder.encodeString.contramap {
    case Local => "Local"
    case S3    => "S3"
  }

}
