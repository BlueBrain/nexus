package ch.epfl.bluebrain.nexus.delta

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.ConversionError
import monix.bio.IO

import scala.util.Try

package object rdf {

  /**
    * Wrap the passed ''value'' on a Try.
    * Convert the Try result into an Either, where a Failure(throwable) is transformed into a Left(ConversionError())
    */
  def tryOrConversionErr[A](value: => A, stage: String): IO[RdfError, A] =
    IO.fromTry(Try(value)).leftMap(err => ConversionError(err.getMessage, stage))
}
