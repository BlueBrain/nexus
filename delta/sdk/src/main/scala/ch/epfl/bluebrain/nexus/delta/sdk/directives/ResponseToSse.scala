package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.emit
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEventLog.ServerSentEventStream
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

import scala.concurrent.duration._

sealed trait ResponseToSse {
  def apply(): Route
}

object ResponseToSse {

  private def apply[E: JsonLdEncoder, A](io: IO[Response[E], ServerSentEventStream])(implicit
      s: Scheduler,
      jo: JsonKeyOrdering,
      cr: RemoteContextResolution
  ): ResponseToSse =
    new ResponseToSse {

      override def apply(): Route =
        onSuccess(io.attempt.runToFuture) {
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
      io: IO[E, ServerSentEventStream]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(io.mapError(Complete(_)))

  implicit def streamValue(
      value: ServerSentEventStream
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(UIO.pure(value))
}
