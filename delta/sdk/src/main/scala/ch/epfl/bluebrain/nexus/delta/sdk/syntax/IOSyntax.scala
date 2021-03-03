package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import cats.Functor
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{DeltaDirectives, Response}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import io.circe.Encoder
import monix.bio.IO

import scala.reflect.ClassTag

trait IOSyntax {
  implicit final def resourceFSyntax[E, A, F[_]: Functor](io: IO[E, F[A]]): IOFunctorOps[E, A, F] = new IOFunctorOps(io)

  implicit final def rejectOrErrSyntax[E: JsonLdEncoder: HttpResponseFields: Encoder, A](
      io: IO[E, A]
  ): RejectionOrErrorOps[E, A] = new RejectionOrErrorOps(io)

}

final class IOFunctorOps[E, A, F[_]: Functor](private val io: IO[E, F[A]]) {

  /**
    * Map value of [[F]] wrapped in an [[IO]].
    *
    * @param f  the mapping function
    * @return   a new [[F]] with value being the result of applying [[f]] to the value of old [[F]]
    */
  def mapValue[B](f: A => B): IO[E, F[B]] = io.map(_.map(f))
}

final class RejectionOrErrorOps[E: JsonLdEncoder: HttpResponseFields: Encoder, A](private val io: IO[E, A]) {

  /**
    * Helper method to convert the error channel of the IO to a [[CustomAkkaRejection]] for a given class of error.
    */
  def rejectOn[R <: E](implicit ct: ClassTag[R]): IO[Response[E], A] =
    DeltaDirectives.rejectOn(io) { case ct(_) => true }
}
