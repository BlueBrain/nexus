package ch.epfl.bluebrain.nexus.cli.types

import io.circe.Decoder

/**
  * A label.
  *
  * @param value the label value
  */
final case class Label(value: String) {
  override def toString: String = value
}

object Label {
  implicit val labelDecoder: Decoder[Label] = Decoder.decodeString.map(Label(_))
}
