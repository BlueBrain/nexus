package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.IllegalLabelFormatError
import io.circe.{Decoder, Encoder}

import scala.util.matching.Regex

/**
  * A safe representation of a name or label that can be positioned as a segment in an Uri without the need to escape it.
  *
  * @param value the string representation of the label
  */
final case class Label private (value: String) {
  override def toString: String = value
}

object Label {

  private[sdk] val regex: Regex = "[a-zA-Z0-9_-]{1,36}".r

  /**
    * Attempts to construct a label from its string representation.
    *
    * @param value the string representation of the Label
    */
  def apply(value: String): Either[FormatError, Label] =
    value match {
      case regex() => Right(new Label(value))
      case _       => Left(IllegalLabelFormatError(value))
    }

  /**
    * Constructs a Label from its string representation without validation in terms of allowed characters or size.
    *
    * @param value the string representation of the label
    */
  def unsafe(value: String): Label =
    new Label(value)

  implicit final val labelEncoder: Encoder[Label] =
    Encoder.encodeString.contramap(_.value)

  implicit final val labelDecoder: Decoder[Label] =
    Decoder.decodeString.emap(str => Label(str).leftMap(_.getMessage))

  implicit val labelJsonLdDecoder: JsonLdDecoder[Label] =
    (cursor: ExpandedJsonLdCursor) =>
      cursor.get[String].flatMap { Label(_).leftMap { e => ParsingFailure(e.getMessage) } }

}
