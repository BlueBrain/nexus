package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.persistence.query.{NoOffset, Sequence, TimeBasedUUID}
import akka.stream.scaladsl.Source
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.JsonValue
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.emit
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import fs2.Stream
import io.circe.Encoder
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import streamz.converter._

sealed trait ResponseToSse {
  def apply(): Route
}

object ResponseToSse {

  private def apply[E: JsonLdEncoder](
      io: IO[Response[E], Stream[Task, Envelope[JsonValue]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    new ResponseToSse {

      private def toSse(envelope: Envelope[JsonValue]) = {
        val json               = envelope.event.encoder(envelope.event.value)
        val id: Option[String] = envelope.offset match {
          case TimeBasedUUID(value) => Some(value.toString)
          case Sequence(value)      => Some(value.toString)
          case NoOffset             => None
        }
        ServerSentEvent(defaultPrinter.print(json.sort), Some(envelope.eventType), id)
      }

      override def apply(): Route =
        onSuccess(io.attempt.runToFuture) {
          case Left(complete: Complete[E]) => emit(complete)
          case Left(reject: Reject[E])     => emit(reject)
          case Right(stream)               => complete(OK, Source.fromGraph[ServerSentEvent, Any](stream.map(toSse).toSource))
        }
    }

  private def apply[E: JsonLdEncoder, A: Encoder](
      io: IO[Response[E], Stream[Task, Envelope[A]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    apply[E](io.map(_.map(_.map(JsonValue(_)))))

  implicit def ioStream[E: JsonLdEncoder: HttpResponseFields, A: Encoder](
      io: IO[E, Stream[Task, Envelope[A]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(io.mapError(Complete(_)))

  implicit def streamValue[E: Encoder](
      value: Stream[Task, Envelope[E]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(UIO.pure(value))

  implicit def ioStreamJsonValue[E: JsonLdEncoder: HttpResponseFields](
      io: IO[E, Stream[Task, Envelope[JsonValue]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(io.mapError(Complete(_)))

  implicit def streamValueJsonValue(
      value: Stream[Task, Envelope[JsonValue]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(UIO.pure(value))

}
