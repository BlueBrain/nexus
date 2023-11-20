package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import DeltaDirectives.emit
import cats.effect.unsafe.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.sse.ServerSentEventStream
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter

import scala.concurrent.duration._

sealed trait ResponseToSse {
  def apply(): Route
}

object ResponseToSse {

  private def apply[E: JsonLdEncoder, A](
      io: IO[Either[Response[E], ServerSentEventStream]]
  )(implicit jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    new ResponseToSse {

      override def apply(): Route =
        onSuccess(io.unsafeToFuture()) {
          case Left(complete: Complete[E]) => emit(complete)
          case Left(reject: Reject[E])     => emit(reject)
          case Right(stream)               =>
            complete(
              OK,
              Source
                .fromGraph[ServerSentEvent, Any](StreamConverter.apply(stream))
                .keepAlive(10.seconds, () => ServerSentEvent.heartbeat)
            )
        }
    }

  implicit def ioStream[E: JsonLdEncoder: HttpResponseFields](
      io: IO[Either[E, ServerSentEventStream]]
  )(implicit jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(io.map(_.left.map(Complete(_))))

  implicit def streamValue(
      value: ServerSentEventStream
  )(implicit jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(IO.pure(Right(value)))
}
