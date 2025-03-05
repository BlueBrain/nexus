package ch.epfl.bluebrain.nexus.delta.sourcing.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.MD5
import ch.epfl.bluebrain.nexus.delta.kernel.error.FormatError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import doobie.{Get, Put}
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}
import pureconfig.ConfigReader
import pureconfig.configurable.genericMapReader
import pureconfig.error.CannotConvert

import scala.util.matching.Regex

/**
  * A safe representation of a name or label that can be positioned as a segment in an Uri without the need to escape
  * it.
  *
  * @param value
  *   the string representation of the label
  */
final case class Label private (value: String) {
  override def toString: String = value
}

object Label {

  private val allowedChars: String = "a-zA-Z0-9_-"

  val regex: Regex = s"[$allowedChars]{1,64}".r

  final case class IllegalLabelFormat(reason: String, details: Option[String] = None) extends FormatError(reason) {
    def message = s"'$reason' did not match the expected label format '${Label.regex.regex}'."
  }

  /**
    * Creates a MD5 value out of the label
    * @param label
    *   the value to hash
    */
  def hash(label: Label): String = MD5.hash(label.value)

  /**
    * Attempts to construct a label from its string representation.
    *
    * @param value
    *   the string representation of the Label
    */
  def apply(value: String): Either[FormatError, Label] =
    value match {
      case regex() => Right(new Label(value))
      case _       => Left(IllegalLabelFormat(value))
    }

  /**
    * Constructs a Label from its string representation without validation in terms of allowed characters or size.
    *
    * @param value
    *   the string representation of the label
    */
  def unsafe(value: String): Label =
    new Label(value)

  /**
    * Attempts to construct a label from its string representation. It will remove all invalid characters and truncate
    * to max length of 64 characters. It will return [[FormatError]] when `value` contains only invalid characters.
    *
    * @param value
    *   the string representation of the Label
    */
  def sanitized(value: String): Either[FormatError, Label] =
    apply(value.replaceAll(s"[^$allowedChars]", "").take(64))

  private def configConvert(value: String): Either[CannotConvert, Label] =
    apply(value).leftMap(e => CannotConvert(value, classOf[Label].getSimpleName, e.getMessage))

  implicit val labelGet: Get[Label] = Get[String].temap(Label(_).leftMap(_.getMessage))
  implicit val labelPut: Put[Label] = Put[String].contramap(_.value)

  implicit val labelKeyDecoder: KeyDecoder[Label] = KeyDecoder.instance(Label(_).toOption)
  implicit val labelKeyEncoder: KeyEncoder[Label] = KeyEncoder.instance(_.value)

  implicit final val labelEncoder: Encoder[Label] =
    Encoder.encodeString.contramap(_.value)

  implicit final val labelDecoder: Decoder[Label] =
    Decoder.decodeString.emap(str => Label(str).leftMap(_.getMessage))

  implicit val labelJsonLdDecoder: JsonLdDecoder[Label] =
    (cursor: ExpandedJsonLdCursor) =>
      cursor.get[String].flatMap { Label(_).leftMap { e => ParsingFailure(e.getMessage) } }

  implicit val labelConfigReader: ConfigReader[Label] = ConfigReader.fromString(configConvert)

  def labelMapReader[V](implicit readerV: ConfigReader[V]): ConfigReader[Map[Label, V]] =
    genericMapReader[Label, V](configConvert)
}
