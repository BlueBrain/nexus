package ch.epfl.bluebrain.nexus.delta.sourcing.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import doobie.{Get, Put}
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

/**
  * A project label along with its parent organization label.
  *
  * @param organization
  *   the parent organization label
  * @param project
  *   the project label
  */
final case class ProjectRef(organization: Label, project: Label) {
  override def toString: String = s"$organization/$project"
}

object ProjectRef {

  val regex = s"^\\/?(${Label.regex.toString()})\\/(${Label.regex.toString()})$$".r

  /**
    * Parse [[ProjectRef]] from a string value e.g. "(/)org/project"
    */
  def parse(value: String): Either[String, ProjectRef] =
    value match {
      case regex(org, proj) => Right(ProjectRef(Label.unsafe(org), Label.unsafe(proj)))
      case s                => Left(s"'$s' is not a ProjectRef")
    }

  /**
    * Constructs a ProjectRef from strings without validation.
    */
  def unsafe(organization: String, project: String): ProjectRef =
    ProjectRef(Label.unsafe(organization), Label.unsafe(project))

  implicit val projectRefGet: Get[ProjectRef] = Get[String].temap(ProjectRef.parse)
  implicit val projectRefPut: Put[ProjectRef] = Put[String].contramap(_.toString)

  implicit val projectRefEncoder: Encoder[ProjectRef]       = Encoder.encodeString.contramap(_.toString)
  implicit val projectRefKeyEncoder: KeyEncoder[ProjectRef] = KeyEncoder.encodeKeyString.contramap(_.toString)
  implicit val projectRefKeyDecoder: KeyDecoder[ProjectRef] = KeyDecoder.instance(parse(_).toOption)
  implicit val projectRefDecoder: Decoder[ProjectRef]       = Decoder.decodeString.emap { parse }

  implicit val projectRefJsonLdEncoder: JsonLdEncoder[ProjectRef] =
    JsonLdEncoder.computeFromCirce(ContextValue.empty)
  implicit val projectRefJsonLdDecoder: JsonLdDecoder[ProjectRef] =
    (cursor: ExpandedJsonLdCursor) => cursor.get[String].flatMap { parse(_).leftMap { e => ParsingFailure(e) } }

}
