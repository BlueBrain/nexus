package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.IllegalTagFormatError
import io.circe.{Decoder, Encoder}

import scala.util.matching.Regex

/**
  * A safe representation of a tag label
  *
  * @param value the string representation of the tag label
  */
final case class TagLabel private (value: String) extends AnyVal {
  override def toString: String = value
}

object TagLabel {

  private[sdk] val regex: Regex = "[\\p{ASCII}]{1,36}".r

  /**
    * Attempts to construct a [[TagLabel]] from its string representation.
    *
    * @param value the string representation of the tag label
    */
  def apply(value: String): Either[FormatError, TagLabel] =
    value match {
      case regex() => Right(new TagLabel(value))
      case _       => Left(IllegalTagFormatError(value))
    }

  /**
    * Constructs a [[TagLabel]] from its string representation without validation in terms of size.
    *
    * @param value the string representation of the tag label
    */
  def unsafe(value: String): TagLabel =
    new TagLabel(value)

  implicit final val tagLabelEncoder: Encoder[TagLabel] =
    Encoder.encodeString.contramap(_.value)

  implicit final val tagLabelDecoder: Decoder[TagLabel] =
    Decoder.decodeString.emap(str => TagLabel(str).leftMap(_.getMessage))

}
