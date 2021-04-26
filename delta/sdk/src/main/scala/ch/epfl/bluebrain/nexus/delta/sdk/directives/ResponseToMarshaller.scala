package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives.{complete, onSuccess, reject}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{HttpResponseFields, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

trait ResponseToMarshaller {
  def apply(): Route
}

object ResponseToMarshaller extends RdfMarshalling {

  private[directives] def apply[E: JsonLdEncoder, A: ToEntityMarshaller](
      uio: UIO[Either[Response[E], Complete[A]]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToMarshaller = () => {
    val ioRoute = uio.flatMap {
      case Left(r: Reject[E])    => UIO.pure(reject(r))
      case Left(e: Complete[E])  => e.value.toCompactedJsonLd.map(r => complete(e.status, e.headers, r.json))
      case Right(v: Complete[A]) => UIO.pure(complete(v.status, v.headers, v.value))
    }
    onSuccess(ioRoute.runToFuture)(identity)
  }

  private[directives] type UseRight[A] = Either[Response[Unit], Complete[A]]

  implicit def uioEntityMarshaller[A: ToEntityMarshaller](
      uio: UIO[A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToMarshaller =
    ResponseToMarshaller(uio.map[UseRight[A]](v => Right(Complete(OK, Seq.empty, v))))

  implicit def ioEntityMarshaller[E: JsonLdEncoder: HttpResponseFields, A: ToEntityMarshaller](
      io: IO[E, A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToMarshaller =
    ResponseToMarshaller(io.mapError(Complete(_)).map(Complete(OK, Seq.empty, _)).attempt)

  implicit def ioResponseEntityMarshaller[E: JsonLdEncoder, A: ToEntityMarshaller](
      io: IO[Response[E], A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToMarshaller =
    ResponseToMarshaller(io.map(Complete(OK, Seq.empty, _)).attempt)
}
