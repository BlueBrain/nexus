package ch.epfl.bluebrain.nexus.cli.sse

import cats.Show
import io.circe.Decoder

/**
  * A safe project label.
  *
  * @param value the string representation
  */
final case class ProjectLabel(value: String)

object ProjectLabel {

  implicit final val projectLabelDecoder: Decoder[ProjectLabel] =
    Decoder.decodeString.map(ProjectLabel.apply)

  implicit final val projectLabelShow: Show[ProjectLabel] = Show.show(_.value)

  implicit final def fromString(string: String): ProjectLabel =
    ProjectLabel(string)

}
