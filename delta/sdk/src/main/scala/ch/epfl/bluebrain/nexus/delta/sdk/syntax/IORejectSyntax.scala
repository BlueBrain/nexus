package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{DeltaDirectives, Response}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import io.circe.Encoder

import scala.reflect.ClassTag

trait IORejectSyntax {

  implicit final def ioRejectOrErrSyntax[E <: Throwable: ClassTag: JsonLdEncoder: HttpResponseFields: Encoder, A](
      io: IO[Either[E, A]]
  ): IORejectionOrErrorOps[E, A] = new IORejectionOrErrorOps(io)

}

final class IORejectionOrErrorOps[E <: Throwable: ClassTag: JsonLdEncoder: HttpResponseFields: Encoder, A](
    private val io: IO[Either[E, A]]
) {

  /**
    * Helper method to convert the error channel of the IO to a [[CustomAkkaRejection]] whenever the passed ''filter''
    * is true. If the [[PartialFunction]] does not apply, the error channel is left untouched.
    */
  def rejectWhen(filter: PartialFunction[E, Boolean]): IO[Either[Response[E], A]] =
    DeltaDirectives.rejectOn(io)(filter)

  /**
    * Helper method to convert the error channel of the IO to a [[CustomAkkaRejection]] for a given class of error.
    */
  def rejectOn[R <: E](implicit ct: ClassTag[R]): IO[Either[Response[E], A]] =
    DeltaDirectives.rejectOn(io) { case ct(_) => true }
}
