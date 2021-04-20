package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model._
import cats.syntax.functor._
import akka.http.scaladsl.model.headers.{Accept, RawHeader}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.persistence.query.{NoOffset, Sequence, TimeBasedUUID}
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.JsonLdValue
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.{emit, jsonLdFormatOrReject, mediaTypes, requestMediaType, unacceptedMediaTypeRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.{Complete, Reject}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.ResponseToJsonLd.{RejOrFailOrComplete, UseLeft, UseRight}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.JsonLdFormat.{Compacted, Expanded}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling._
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

  private[directives] type UseLeft[A]             = Either[Response[A], Complete[Unit]]
  private[directives] type UseRight[A]            = Either[Response[Unit], Complete[A]]
  private[directives] type RejOrFailOrComplete[E] =
    Either[Either[Reject[E], Complete[JsonLdValue]], Complete[JsonLdValue]]

  private[directives] def apply[E](
      uio: UIO[RejOrFailOrComplete[E]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    new ResponseToJsonLd {

      override def apply(statusOverride: Option[StatusCode]): Route = {

        val uioFinal = uio.map(_.map(value => value.copy(status = statusOverride.getOrElse(value.status))))

        def marshaller[R: ToEntityMarshaller](handle: JsonLdValue => IO[RdfError, R]): Route = {
          val ioRoute = uioFinal.flatMap {
            case Left(Left(rej))                               => UIO.pure(reject(rej))
            case Left(Right(Complete(status, headers, value))) => handle(value).map(complete(status, headers, _))
            case Right(Complete(status, headers, value))       => handle(value).map(complete(status, headers, _))
          }
          onSuccess(ioRoute.runToFuture)(identity)
        }

        requestMediaType {
          case mediaType if mediaType == `application/ld+json` =>
            jsonLdFormatOrReject {
              case Expanded  => marshaller(v => v.encoder.expand(v.value))
              case Compacted => marshaller(v => v.encoder.compact(v.value))
            }

          case mediaType if mediaType == `application/json` =>
            jsonLdFormatOrReject {
              case Expanded  => marshaller(v => v.encoder.expand(v.value).map(_.json))
              case Compacted => marshaller(v => v.encoder.compact(v.value).map(_.json))
            }

          case mediaType if mediaType == `application/n-triples` => marshaller(v => v.encoder.ntriples(v.value))

          case mediaType if mediaType == `application/n-quads` => marshaller(v => v.encoder.nquads(v.value))

          case mediaType if mediaType == `text/vnd.graphviz` => marshaller(v => v.encoder.dot(v.value))

          case _ => reject(unacceptedMediaTypeRejection(mediaTypes))
        }
      }
    }

  private[directives] def apply[E: JsonLdEncoder, A: JsonLdEncoder](
      uio: UIO[Either[Response[E], Complete[A]]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    apply(uio.map[RejOrFailOrComplete[E]] {
      case Right(c: Complete[A]) => Right(c.map(JsonLdValue(_)))
      case Left(c: Complete[E])  => Left(Right(c.map(JsonLdValue(_))))
      case Left(rej: Reject[E])  => Left(Left(rej))
    })

  private[directives] def fromFile[E: JsonLdEncoder](
      io: IO[Response[E], FileResponse]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    new ResponseToJsonLd {

      // From the RFC 2047: "=?" charset "?" encoding "?" encoded-text "?="
      private def attachmentString(filename: String): String = {
        val encodedFilename = Base64.getEncoder.encodeToString(filename.getBytes(StandardCharsets.UTF_8))
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

  private[directives] def fromStream[E: JsonLdEncoder](
      io: IO[Response[E], Stream[Task, Envelope[JsonLdValue]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    new ResponseToJsonLd {

      private def toSse(envelope: Envelope[JsonLdValue]) =
        envelope.event.encoder.compact(envelope.event.value).map { jsonLd =>
          val id: String = envelope.offset match {
            case TimeBasedUUID(value) => value.toString
            case Sequence(value)      => value.toString
            case NoOffset             => "-1"
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

  private[directives] def fromStream[E: JsonLdEncoder, A: JsonLdEncoder](
      io: IO[Response[E], Stream[Task, Envelope[A]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd = {
    fromStream[E](io.map(_.map(_.map(JsonLdValue(_)))))
  }

}

sealed trait FileBytesInstances extends StreamInstances {
  implicit def ioFileBytesWithReject[E: JsonLdEncoder](
      io: IO[Response[E], FileResponse]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd.fromFile(io)

  implicit def ioFileBytes[E: JsonLdEncoder: HttpResponseFields](
      io: IO[E, FileResponse]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd.fromFile(io.mapError(Complete(_)))

  implicit def fileBytesValue(
      value: FileResponse
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd.fromFile(UIO.pure(value))

}

sealed trait StreamInstances extends ValueInstances {

  implicit def ioStreamWithReject[E: JsonLdEncoder, A <: Event: JsonLdEncoder](
      io: IO[Response[E], Stream[Task, Envelope[A]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    ResponseToJsonLd.fromStream(io)

  implicit def ioStream[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
      io: IO[E, Stream[Task, Envelope[A]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    ResponseToJsonLd.fromStream(io.mapError(Complete(_)))

  implicit def streamValue[E: JsonLdEncoder](
      value: Stream[Task, Envelope[E]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    ResponseToJsonLd.fromStream(UIO.pure(value))

  implicit def ioStreamJsonLdValue[E: JsonLdEncoder: HttpResponseFields](
      io: IO[E, Stream[Task, Envelope[JsonLdValue]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    ResponseToJsonLd.fromStream(io.mapError(Complete(_)))

  implicit def streamValueJsonLdValue(
      value: Stream[Task, Envelope[JsonLdValue]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ResponseToJsonLd =
    ResponseToJsonLd.fromStream(UIO.pure(value))
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

  implicit def ioValueWithReject[E: JsonLdEncoder, A: JsonLdEncoder](
      io: IO[Response[E], A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.map(Complete(OK, Seq.empty, _)).attempt)

  implicit def ioValue[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
      io: IO[E, A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.mapError(Complete(_)).map(Complete(OK, Seq.empty, _)).attempt)

  implicit def ioReferenceExchangeValue[E: JsonLdEncoder: HttpResponseFields](
      io: IO[E, JsonLdValue]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ResponseToJsonLd =
    ResponseToJsonLd(io.attempt.map[RejOrFailOrComplete[E]] {
      case Left(e)      => Left(Right(Complete(e).map[JsonLdValue](JsonLdValue(_))))
      case Right(value) => Right(Complete(OK, Seq.empty, value))
    })

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
