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
import ch.epfl.bluebrain.nexus.delta.sdk.akka.StreamConverter
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.emit
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseConverter
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EnvelopeStream
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

import scala.concurrent.duration._

sealed trait ResponseToSseNew {
  def apply(): Route
}

object ResponseToSseNew {

  private def apply[E: JsonLdEncoder, A](io: IO[Response[E], EnvelopeStream[_, A]])(implicit
      sseConverter: SseConverter[A],
      s: Scheduler,
      jo: JsonKeyOrdering,
      cr: RemoteContextResolution
  ): ResponseToSseNew =
    new ResponseToSseNew {

      override def apply(): Route =
        onSuccess(io.attempt.runToFuture) {
          case Left(complete: Complete[E]) => emit(complete)
          case Left(reject: Reject[E])     => emit(reject)
          case Right(stream)               =>
            complete(
              OK,
              Source
                .fromGraph[ServerSentEvent, Any](StreamConverter.apply(stream.evalMap(sseConverter.apply)))
                .keepAlive(10.seconds, () => ServerSentEvent.heartbeat)
            )
        }
    }

  implicit def ioStream[E: JsonLdEncoder: HttpResponseFields, A: SseConverter](
      io: IO[E, EnvelopeStream[_, A]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSseNew =
    ResponseToSseNew(io.mapError(Complete(_)))

  implicit def streamValue[A: SseConverter](
      value: EnvelopeStream[_, A]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSseNew =
    ResponseToSseNew(UIO.pure(value))
}
