package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import cats.Order
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

  private def parse(value: String): Either[String, ProjectRef] =
    value match {
      case regex(org, proj) => Right(ProjectRef(Label.unsafe(org), Label.unsafe(proj)))
      case s                => Left(s"'$s' is not a ProjectRef")
    }

  /**
    * Constructs a ProjectRef from strings without validation.
    */
  def unsafe(organization: String, project: String): ProjectRef =
    ProjectRef(Label.unsafe(organization), Label.unsafe(project))

  implicit val projectRefEncoder: Encoder[ProjectRef] = Encoder.encodeString.contramap(_.toString)
  implicit val projectRefDecoder: Decoder[ProjectRef] = Decoder.decodeString.emap { parse }

  implicit final val projectRefOrder: Order[ProjectRef] = Order.by(_.toString)

  implicit val projectRefJsonLdDecoder: JsonLdDecoder[ProjectRef] =
    (cursor: ExpandedJsonLdCursor) => cursor.get[String].flatMap { parse(_).leftMap { e => ParsingFailure(e) } }
}
