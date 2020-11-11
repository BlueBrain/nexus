package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import io.circe.{Decoder, Encoder}

/**
  * A project label along with its parent organization label.
  *
  * @param organization the parent organization label
  * @param project      the project label
  */
final case class ProjectRef(organization: Label, project: Label) {
  override def toString: String = s"$organization/$project"
}

object ProjectRef {

  private val regex = s"^(${Label.regex.toString()})\\/(${Label.regex.toString()})$$".r

  /**
    * Constructs a ProjectRef from strings without validation.
    */
  def unsafe(organization: String, project: String): ProjectRef =
    ProjectRef(Label.unsafe(organization), Label.unsafe(project))

  implicit val projectRefEncoder: Encoder[ProjectRef] = Encoder.encodeString.contramap(_.toString)
  implicit val projectRefDecoder: Decoder[ProjectRef] = Decoder.decodeString.emap {
    case regex(org, proj) => Right(ProjectRef(Label.unsafe(org), Label.unsafe(proj)))
    case s                => Left(s"'$s' is not a ProjectRef")
  }

}
