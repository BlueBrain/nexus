package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.IllegalTagFormatError
import io.circe.{Decoder, Encoder}

/**
  * A safe representation of a tag label
  *
  * @param value the string representation of the tag label
  */
final case class TagLabel private (value: String) {
  override def toString: String = value
}

object TagLabel {

  private[sdk] val maxLength = 32

  /**
    * Attempts to construct a [[TagLabel]] from its string representation.
    *
    * @param value the string representation of the tag label
    */
  def apply(value: String): Either[FormatError, TagLabel] =
    if (value.length > maxLength)
      Left(IllegalTagFormatError(value))
    else
      Right(new TagLabel(value))

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
