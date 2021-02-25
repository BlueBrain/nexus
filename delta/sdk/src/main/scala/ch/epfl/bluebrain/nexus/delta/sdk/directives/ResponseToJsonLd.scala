package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{StatusCode, _}
import akka.http.scaladsl.model.headers.{Accept, RawHeader}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.persistence.query.{NoOffset, Sequence, TimeBasedUUID}
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{emit, jsonLdFormatOrReject, mediaTypes, requestMediaType, unacceptedMediaTypeRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.ResponseToJsonLd.{UseLeft, UseRight}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.JsonLdFormat.{Compacted, Expanded}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import streamz.converter._

import java.nio.charset.StandardCharsets
import java.util.Base64

sealed trait ResponseToJsonLd {
  def apply(statusOverride: Option[StatusCode]): Route
}

object ResponseToJsonLd extends FileBytesInstances {

  private[directives] type UseLeft[A]  = Either[Response[A], Complete[Unit]]
  private[directives] type UseRight[A] = Either[Response[Unit], Complete[A]]

  private[directives] def apply[E: JsonLdEncoder, A: JsonLdEncoder](
      uio: UIO[Either[Response[E], Complete[A]]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    new ResponseToJsonLd {

      override def apply(statusOverride: Option[StatusCode]): Route = {

        val uioFinal = uio.map(_.map(value => value.copy(status = statusOverride.getOrElse(value.status))))

        def marshaller[R: ToEntityMarshaller](
            error: Complete[E] => IO[RdfError, R],
            success: Complete[A] => IO[RdfError, R]
        ): Route = {

          val ioRoute = uioFinal.flatMap {
            case Left(r: Reject[E])    => UIO.pure(reject(r))
            case Left(e: Complete[E])  => error(e).map(complete(e.status, e.headers, _))
            case Right(v: Complete[A]) => success(v).map(complete(v.status, v.headers, _))
          }
          onSuccess(ioRoute.runToFuture)(identity)
        }

        requestMediaType {
          case mediaType if mediaType == `application/ld+json` =>
            jsonLdFormatOrReject {
              case Expanded  => marshaller(_.value.toExpandedJsonLd, _.value.toExpandedJsonLd)
              case Compacted => marshaller(_.value.toCompactedJsonLd, _.value.toCompactedJsonLd)
            }

          case mediaType if mediaType == `application/json` =>
            jsonLdFormatOrReject {
              case Expanded  => marshaller(_.value.toExpandedJsonLd.map(_.json), _.value.toExpandedJsonLd.map(_.json))
              case Compacted => marshaller(_.value.toCompactedJsonLd.map(_.json), _.value.toCompactedJsonLd.map(_.json))
            }

          case mediaType if mediaType == `application/n-triples` => marshaller(_.value.toNTriples, _.value.toNTriples)

          case mediaType if mediaType == `text/vnd.graphviz` => marshaller(_.value.toDot, _.value.toDot)

          case _ => reject(unacceptedMediaTypeRejection(mediaTypes))
        }
      }
    }

  private[directives] def apply[E: JsonLdEncoder](
      io: IO[Response[E], FileResponse]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    new ResponseToJsonLd {

      // From the RFC 2047: "=?" charset "?" encoding "?" encoded-text "?="
      private def attachmentString(filename: String): String = {
        val encodedFilename = Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8))
        s"=?UTF-8?B?$encodedFilename?="
      }

      override def apply(statusOverride: Option[StatusCode]): Route =
        onSuccess(io.attempt.runToFuture) {
          case Left(complete: Complete[E]) => emit(complete)
          case Left(reject: Reject[E])     => emit(reject)
          case Right(response)             =>
            headerValueByType(Accept) { accept =>
              if (accept.mediaRanges.exists(_.matches(response.contentType.mediaType))) {
                val encodedFilename = attachmentString(response.filename)
                respondWithHeaders(RawHeader("Content-Disposition", s"""attachment; filename="$encodedFilename"""")) {
                  encodeResponse {
                    complete(statusOverride.getOrElse(OK), HttpEntity(response.contentType, response.content))
                  }
                }
              } else
                reject(unacceptedMediaTypeRejection(Seq(response.contentType.mediaType)))
            }
        }
    }

  private[directives] def apply[E: JsonLdEncoder, A <: Event: JsonLdEncoder](
      io: IO[Response[E], Stream[Task, Envelope[A]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    new ResponseToJsonLd {

      private def toSse(envelope: Envelope[A]) =
        envelope.event.toCompactedJsonLd.map { jsonLd =>
          val id: String = envelope.offset match {
            case TimeBasedUUID(value) => value.toString
            case Sequence(value)      => value.toString
            case NoOffset             => -1L.toString
          }
          ServerSentEvent(
            data = defaultPrinter.print(jsonLd.json.sort),
            eventType = Some(envelope.eventType),
            id = Some(id)
          )
        }

      override def apply(statusOverride: Option[StatusCode]): Route =
        onSuccess(io.attempt.runToFuture) {
          case Left(complete: Complete[E]) => emit(complete)
          case Left(reject: Reject[E])     => emit(reject)
          case Right(stream)               =>
            complete(
              statusOverride.getOrElse(OK),
              Source.fromGraph[ServerSentEvent, Any](stream.evalMap(toSse).toSource)
            )
        }
    }

}

sealed trait FileBytesInstances extends StreamInstances {
  implicit def ioFileBytesWithReject[E: JsonLdEncoder](
      io: IO[Response[E], FileResponse]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io)

  implicit def ioFileBytes[E: JsonLdEncoder: HttpResponseFields](
      io: IO[E, FileResponse]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.mapError(Complete(_)))

  implicit def fileBytesValue(
      value: FileResponse
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(UIO.pure(value))

}

sealed trait StreamInstances extends ValueInstances {

  implicit def ioStreamWithReject[E: JsonLdEncoder, A <: Event: JsonLdEncoder](
      io: IO[Response[E], Stream[Task, Envelope[A]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    ResponseToJsonLd(io)

  implicit def ioStream[E: JsonLdEncoder: HttpResponseFields, A <: Event: JsonLdEncoder](
      io: IO[E, Stream[Task, Envelope[A]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    ResponseToJsonLd(io.mapError(Complete(_)))

  implicit def streamValue[E <: Event: JsonLdEncoder](
      value: Stream[Task, Envelope[E]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    ResponseToJsonLd(UIO.pure(value))
}

sealed trait ValueInstances extends LowPriorityValueInstances {

  implicit def uioValueWithReject[E: JsonLdEncoder](
      io: UIO[Reject[E]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.map[UseLeft[E]](Left(_)))

  implicit def uioValue[A: JsonLdEncoder](
      io: UIO[A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.map[UseRight[A]](v => Right(Complete(OK, Seq.empty, v))))

  implicit def uioValueSearchResults[A](
      io: UIO[SearchResults[A]]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      jo: JsonKeyOrdering,
      S: SearchEncoder[A],
      extraCtx: ContextValue
  ): ResponseToJsonLd =
    ResponseToJsonLd(io.map[UseRight[SearchResults[A]]](v => Right(Complete(OK, Seq.empty, v))))

  implicit def ioValueWithReject[E: JsonLdEncoder, A: JsonLdEncoder](
      io: IO[Response[E], A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.map(Complete(OK, Seq.empty, _)).attempt)

  implicit def ioValue[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
      io: IO[E, A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.mapError(Complete(_)).map(Complete(OK, Seq.empty, _)).attempt)

  implicit def ioValueSearchResults[E: JsonLdEncoder: HttpResponseFields, A](
      io: IO[E, SearchResults[A]]
  )(implicit
      s: Scheduler,
      cr: RemoteContextResolution,
      jo: JsonKeyOrdering,
      S: SearchEncoder[A],
      extraCtx: ContextValue
  ): ResponseToJsonLd =
    ResponseToJsonLd(io.mapError(Complete(_)).map(Complete(OK, Seq.empty, _)).attempt)

  implicit def rejectValue[E: JsonLdEncoder](
      value: Reject[E]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(UIO.pure[UseLeft[E]](Left(value)))

  implicit def completeValue[A: JsonLdEncoder](
      value: Complete[A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(UIO.pure[UseRight[A]](Right(value)))

  implicit def valueWithHttpResponseFields[A: JsonLdEncoder: HttpResponseFields](
      value: A
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(UIO.pure[UseRight[A]](Right(Complete(value))))
}

sealed trait LowPriorityValueInstances {
  implicit def valueWithoutHttpResponseFields[A: JsonLdEncoder](
      value: A
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(UIO.pure[UseRight[A]](Right(Complete(OK, Seq.empty, value))))
}
