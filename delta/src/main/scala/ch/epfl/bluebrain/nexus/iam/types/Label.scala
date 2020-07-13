package ch.epfl.bluebrain.nexus.iam.types

import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}
import io.circe.{Decoder, Encoder}

import scala.util.matching.Regex

/**
  * A label that's safe to be used as an iri segment value without the need to encode it.
  *
  * @param value the underlying value
  */
final case class Label private (value: String) {

  /**
    * Builds an AbsoluteIri by suffixing the label to the provided base, separated by slash. If the provided ''base''
    * ends with a '/' the label is appended directly, otherwise a slash is injected before the label.
    *
    * @param base the base absolute iri to prepend to the label
    */
  def toIri(base: AbsoluteIri): AbsoluteIri =
    base + value

  /**
    * Builds an Path by suffixing the label to the /; for example, if the label has value "label" this returns "/label".
    */
  def toPath: Path =
    Path./ + value
}

object Label {
  private[types] val regex: Regex = "[a-z0-9]{1,32}".r

  /**
    * Attempts to construct a label, applying the necessary format validation.
    *
    * @param value the string value to lift into a Label
    * @return Left(error) if the provided value doesn't match the expected format or Right(label) otherwise.
    */
  final def apply(value: String): Either[String, Label] =
    value match {
      case regex() => Right(new Label(value))
      case _       => Left(s"Label '$value' does not match pattern '${regex.regex}'")
    }

  /**
    * Attempts to construct a ''Label'', throwing an [[IllegalArgumentException]] if the provided value does not match
    * the expected label format.
    *
    * @param value the string value to lift into a Label
    * @throws IllegalArgumentException if the provided value does not match the expected Label format
    */
  @throws[IllegalArgumentException]("if the provided value does not match the expected Label format")
  final def unsafe(value: String): Label =
    apply(value).fold(err => throw new IllegalArgumentException(err), identity)

  implicit final val labelEncoder: Encoder[Label] =
    Encoder.encodeString.contramap[Label](_.value)

  implicit final val labelDecoder: Decoder[Label] =
    Decoder.decodeString.emap(Label.apply)
}
