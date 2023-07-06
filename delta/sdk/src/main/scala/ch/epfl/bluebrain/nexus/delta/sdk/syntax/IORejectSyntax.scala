package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{DeltaDirectives, Response}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import io.circe.Encoder
import monix.bio.IO

import scala.reflect.ClassTag

trait IORejectSyntax {
  implicit final def rejectOrErrSyntax[E: JsonLdEncoder: HttpResponseFields: Encoder, A](
      io: IO[E, A]
  ): RejectionOrErrorOps[E, A] = new RejectionOrErrorOps(io)

}

final class RejectionOrErrorOps[E: JsonLdEncoder: HttpResponseFields: Encoder, A](private val io: IO[E, A]) {

  /**
    * Helper method to convert the error channel of the IO to a [[CustomAkkaRejection]] whenever the passed ''filter''
    * is true. If the [[PartialFunction]] does not apply, the error channel is left untouched.
    */
  def rejectWhen(filter: PartialFunction[E, Boolean]): IO[Response[E], A] =
    DeltaDirectives.rejectOn(io)(filter)

  /**
    * Helper method to convert the error channel of the IO to a [[CustomAkkaRejection]] for a given class of error.
    */
  def rejectOn[R <: E](implicit ct: ClassTag[R]): IO[Response[E], A] =
    DeltaDirectives.rejectOn(io) { case ct(_) => true }
}
