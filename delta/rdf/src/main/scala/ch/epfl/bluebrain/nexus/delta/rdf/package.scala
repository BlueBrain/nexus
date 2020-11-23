package ch.epfl.bluebrain.nexus.delta

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.ConversionError
import monix.bio.IO

import scala.util.Try

package object rdf {

  private[rdf] def ioTryOrConversionErr[A](value: => A, stage: String): IO[RdfError, A] =
    IO.fromEither(tryOrConversionErr(value, stage))

  private[rdf] def tryOrConversionErr[A](value: => A, stage: String): Either[RdfError, A] =
    Try(value).toEither.leftMap(err => ConversionError(err.getMessage, stage))
}
