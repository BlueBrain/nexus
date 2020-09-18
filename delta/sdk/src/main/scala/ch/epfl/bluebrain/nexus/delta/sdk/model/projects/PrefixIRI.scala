package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.{IllegalIRIFormatError, IllegalPrefixIRIFormatError}
import org.apache.jena.iri.IRI

/**
  * An IRI that ends with ''/'' or ''#''
  */
final case class PrefixIRI private (value: IRI) extends AnyVal {
  override def toString: String = value.toString
}

object PrefixIRI {

  /**
    * Attempts to construct a [[PrefixIRI]] from its IRI representation.
    *
   * @param value the iri
    */
  final def apply(value: IRI): Either[FormatError, PrefixIRI] =
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
  final def unsafe(value: IRI): PrefixIRI =
    new PrefixIRI(value)

}
