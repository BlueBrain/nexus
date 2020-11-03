package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.{IllegalIRIFormatError, IllegalPrefixIRIFormatError}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.{Decoder, Encoder}

/**
  * An Iri that ends with ''/'' or ''#''
  */
final case class PrefixIRI private (value: Iri) extends AnyVal {
  override def toString: String = value.toString
}

object PrefixIRI {

  /**
    * Attempts to construct a [[PrefixIRI]] from its Iri representation.
    *
    * @param value the iri
    */
  final def apply(value: Iri): Either[FormatError, PrefixIRI] =
    Option.when(value.isPrefixMapping)(new PrefixIRI(value)).toRight(IllegalPrefixIRIFormatError(value))

  /**
    * Attempts to construct a [[PrefixIRI]] from its string representation.
    *
    * @param value the string representation of an iri
    */
  final def apply(value: String): Either[FormatError, PrefixIRI] =
    value.toIri.leftMap(_ => IllegalIRIFormatError(value)).flatMap(apply)

  /**
    * Construct [[PrefixIRI]] without performing any checks.
    *
    * @param value the iri
    */
  final def unsafe(value: Iri): PrefixIRI =
    new PrefixIRI(value)

  implicit final val prefixIriDecoder: Decoder[PrefixIRI] =
    Decoder.decodeString.emap(apply(_).leftMap(_.getMessage))

  implicit final val iriOrBNodeEncoder: Encoder[PrefixIRI] =
    Encoder.encodeString.contramap(_.toString)

}
