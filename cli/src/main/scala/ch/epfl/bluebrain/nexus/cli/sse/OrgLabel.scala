package ch.epfl.bluebrain.nexus.cli.sse

import cats.Show
import io.circe.Decoder

/**
  * A safe organization label.
  *
  * @param value the string representation
  */
final case class OrgLabel(value: String)

object OrgLabel {

  implicit final val orgLabelDecoder: Decoder[OrgLabel] =
    Decoder.decodeString.map(OrgLabel.apply)

  implicit final val orgLabelShow: Show[OrgLabel] = Show.show(_.value)

  implicit final def fromString(string: String): OrgLabel =
    OrgLabel(string)

}
