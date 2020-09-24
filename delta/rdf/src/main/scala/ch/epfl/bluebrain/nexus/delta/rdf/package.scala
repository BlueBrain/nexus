package ch.epfl.bluebrain.nexus.delta

import org.apache.jena.iri.{IRI, IRIFactory}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.ConversionError
import monix.bio.IO

import scala.util.Try

package object rdf {

  private val iriFactory = IRIFactory.iriImplementation()

  /**
    * Attempts to construct an IRI, returning a Left when it does not have the correct IRI format.
    */
  def iri(string: String): Either[String, IRI] = {
    val iri = iriUnsafe(string)
    Option.when(!iri.hasViolation(false))(iri).toRight(s"'$string' is not an IRI")
  }

  /**
    * Construct an IRI without checking the validity of the format.
    */
  def iriUnsafe(string: String): IRI =
    iriFactory.create(string)

  /**
    * Wrap the passed ''value'' on a Try.
    * Convert the Try result into an Either, where a Failure(throwable) is transformed into a Left(ConversionError())
    */
  def tryOrConversionErr[A](value: => A, stage: String): IO[RdfError, A] =
    IO.fromTry(Try(value)).leftMap(err => ConversionError(err.getMessage, stage))
}
