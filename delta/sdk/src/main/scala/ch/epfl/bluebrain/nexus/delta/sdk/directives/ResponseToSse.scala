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
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchUuids
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.emit
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import fs2.Stream
import io.circe.Encoder
import io.circe.syntax._
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import streamz.converter._

import scala.concurrent.duration._

sealed trait ResponseToSse {
  def apply(): Route
}

object ResponseToSse {

  private def apply[E: JsonLdEncoder, A <: Event](
      io: IO[Response[E], Stream[Task, Envelope[JsonValue.Aux[A]]]]
  )(implicit fetchUuids: FetchUuids, s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    new ResponseToSse {

      private def toSse(envelope: Envelope[JsonValue.Aux[A]]) = {
        val jsonEvent = envelope.event.encoder.encodeObject(envelope.event.value)
        val uioJson   = envelope.event.value match {
          case ev: ProjectScopedEvent =>
            fetchUuids(ev.project).map(_.fold(jsonEvent) { case (orgUuid, projUuid) =>
              jsonEvent.add("_organizationUuid", orgUuid.asJson).add("_projectUuid", projUuid.asJson)
            })
          case _                      => UIO.pure(jsonEvent)
        }
        uioJson.map { json =>
          val id: Option[String] = envelope.offset match {
            case TimeBasedUUID(value) => Some(value.toString)
            case Sequence(value)      => Some(value.toString)
            case NoOffset             => None
            case _                    => None
          }
          ServerSentEvent(defaultPrinter.print(json.asJson.sort), Some(envelope.eventType), id)
        }
      }

      override def apply(): Route =
        onSuccess(io.attempt.runToFuture) {
          case Left(complete: Complete[E]) => emit(complete)
          case Left(reject: Reject[E])     => emit(reject)
          case Right(stream)               =>
            complete(
              OK,
              Source
                .fromGraph[ServerSentEvent, Any](stream.evalMap(toSse).toSource)
                .keepAlive(10.seconds, () => ServerSentEvent.heartbeat)
            )
        }
    }

  private def apply[E: JsonLdEncoder, A <: Event: Encoder.AsObject](
      io: IO[Response[E], Stream[Task, Envelope[A]]]
  )(implicit fetchUuids: FetchUuids, s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    apply[E, A](io.map(_.map(_.map(JsonValue(_)))))

  implicit def ioStream[E: JsonLdEncoder: HttpResponseFields, A <: Event: Encoder.AsObject](
      io: IO[E, Stream[Task, Envelope[A]]]
  )(implicit fetchUuids: FetchUuids, s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(io.mapError(Complete(_)))

  implicit def streamValue[A <: Event: Encoder.AsObject](
      value: Stream[Task, Envelope[A]]
  )(implicit fetchUuids: FetchUuids, s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(UIO.pure(value))

  implicit def ioStreamJsonValue[E: JsonLdEncoder: HttpResponseFields, A <: Event](
      io: IO[E, Stream[Task, Envelope[JsonValue.Aux[A]]]]
  )(implicit fetchUuids: FetchUuids, s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(io.mapError(Complete(_)))

  implicit def streamValueJsonValue[A <: Event](
      value: Stream[Task, Envelope[JsonValue.Aux[A]]]
  )(implicit fetchUuids: FetchUuids, s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToSse =
    ResponseToSse(UIO.pure(value))

}
