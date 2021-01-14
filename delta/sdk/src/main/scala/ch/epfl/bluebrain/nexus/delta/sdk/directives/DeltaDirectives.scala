package ch.epfl.bluebrain.nexus.delta.sdk.directives

import java.util.{Base64, UUID}
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, RawHeader}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.ContentNegotiator.Alternative
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import akka.stream.scaladsl.Source
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{HttpResponseFields, JsonLdFormat, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.HeadersUtils
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import streamz.converter._

import java.nio.charset.StandardCharsets
import scala.util.Try

object DeltaDirectives extends UriDirectives with RdfMarshalling {

  // order is important
  val mediaTypes: List[MediaType.WithFixedCharset] =
    List(
      `application/ld+json`,
      `application/json`,
      `application/n-triples`,
      `text/vnd.graphviz`
    )

  def unacceptedMediaTypeRejection(values: Seq[MediaType]): UnacceptedResponseContentTypeRejection =
    UnacceptedResponseContentTypeRejection(values.map(mt => Alternative(mt)).toSet)

  /**
    * Extracts an [[Offset]] value from the ''Last-Event-ID'' header, defaulting to [[NoOffset]]. An invalid value will
    * result in an [[MalformedHeaderRejection]].
    */
  def lastEventId: Directive1[Offset] = {
    optionalHeaderValueByType(`Last-Event-ID`).flatMap {
      case Some(value) =>
        val timeBasedUUID = Try(TimeBasedUUID(UUID.fromString(value.id))).toOption
        val sequence      = value.id.toLongOption.map(Sequence)
        timeBasedUUID orElse sequence match {
          case Some(value) => provide(value)
          case None        =>
            reject(
              MalformedHeaderRejection(
                `Last-Event-ID`.name,
                s"Invalid '${`Last-Event-ID`.name}' header value '${value.id}', expected either a Long value or a TimeBasedUUID."
              )
            )
        }
      case None        => provide(NoOffset)
    }
  }

  private def jsonldFormat[A: JsonLdEncoder, E: JsonLdEncoder: HttpResponseFields](
      uio: UIO[Either[E, A]],
      successStatus: => StatusCode,
      successHeaders: => Seq[HttpHeader]
  )(implicit cr: RemoteContextResolution): Directive1[IO[RdfError, Result]] =
    jsonLdFormat.map {
      case JsonLdFormat.Compacted =>
        uio.flatMap {
          case Left(err)    => err.toCompactedJsonLd.map(v => (err.status, err.headers, v))
          case Right(value) => value.toCompactedJsonLd.map(v => (successStatus, successHeaders, v))
        }
      case JsonLdFormat.Expanded  =>
        uio.flatMap {
          case Left(err)    => err.toExpandedJsonLd.map(v => (err.status, err.headers, v))
          case Right(value) => value.toExpandedJsonLd.map(v => (successStatus, successHeaders, v))
        }
    }

  private def requestMediaType: Directive1[MediaType] =
    extractRequest.flatMap { req =>
      HeadersUtils.findFirst(req.headers, mediaTypes) match {
        case Some(value) => provide(value)
        case None        => reject(unacceptedMediaTypeRejection(mediaTypes))
      }
    }

  /**
    * Completes the current Route with the provided conversion to Json-LD
    */
  def emit(response: ToResponseJsonLd): Route = response(None)

  /**
    * Completes the current Route with the provided status code and conversion to Json-LD
    */
  def emit(status: StatusCode, response: ToResponseJsonLd): Route = response(Some(status))

  /**
    * Completes the current Route discarding the entity and completing with the provided conversion to Json-LD
    */
  def discardEntityAndEmit(response: DiscardEntityToResponseJsonLd): Route = response(None)

  /**
    * Completes the current Route discarding the entity and completing with the provided
    * status code and conversion to Json-LD
    */
  def discardEntityAndEmit(status: StatusCode, response: DiscardEntityToResponseJsonLd): Route = response(Some(status))

  private def toResponseJsonLd[A: JsonLdEncoder](
      status: => StatusCode,
      headers: => Seq[HttpHeader],
      uio: UIO[A]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): Route =
    toResponseJsonLd(status, headers, uio.map[Either[Unit, A]](Right(_)))

  private def toResponseJsonLd[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
      status: => StatusCode,
      headers: => Seq[HttpHeader],
      uio: UIO[Either[E, A]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): Route =
    requestMediaType {
      case mediaType if mediaType == `application/ld+json` =>
        jsonldFormat(uio, status, headers).apply { formatted =>
          onSuccess(formatted.runToFuture) { case (status, headers, jsonLd) =>
            complete(status, headers, jsonLd)
          }
        }

      case mediaType if mediaType == `application/json` =>
        jsonldFormat(uio, status, headers).apply { formatted =>
          onSuccess(formatted.runToFuture) { case (status, headers, jsonLd) =>
            complete(status, headers, jsonLd.json)
          }
        }

      case mediaType if mediaType == `application/n-triples` =>
        val formatted = uio.flatMap {
          case Left(err)    => err.toNTriples.map(v => (err.status, err.headers, v))
          case Right(value) => value.toNTriples.map(v => (status, headers, v))
        }
        onSuccess(formatted.runToFuture) { case (status, headers, ntriples) =>
          complete(status, headers, ntriples)
        }

      case mediaType if mediaType == `text/vnd.graphviz` =>
        val formatted = uio.flatMap {
          case Left(err)    => err.toDot.map(v => (err.status, err.headers, v))
          case Right(value) => value.toDot.map(v => (status, headers, v))
        }
        onSuccess(formatted.runToFuture) { case (status, headers, dot) =>
          complete(status, headers, dot)
        }

      case _ =>
        reject(UnacceptedResponseContentTypeRejection(mediaTypes.toSet.map((mt: MediaType) => Alternative(mt))))
    }

  private def toResponseJsonLd[E: JsonLdEncoder: HttpResponseFields, A <: Event: JsonLdEncoder](
      status: => StatusCode,
      io: IO[E, Stream[Task, Envelope[A]]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): Route =
    onSuccess(io.attempt.runToFuture) {
      case Left(err)     => emit(err)
      case Right(stream) => emit(status, stream)
    }

  private def toResponseJsonLd[A <: Event: JsonLdEncoder](
      status: => StatusCode,
      stream: Stream[Task, Envelope[A]]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): Route =
    complete(status, Source.fromGraph[ServerSentEvent, Any](stream.evalMap(sseEncode[A](_)).toSource))

  private def toResponseJsonLd[E: JsonLdEncoder: HttpResponseFields](
      status: => StatusCode,
      io: IO[E, FileResponse]
  )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): Route = {

    onSuccess(io.attempt.runToFuture) {
      case Left(err)       => emit(err)
      case Right(response) =>
        val encodedFilename = attachmentString(response.filename)
        respondWithHeaders(RawHeader("Content-Disposition", s"""attachment; filename="$encodedFilename"""")) {
          encodeResponse {
            complete(status, HttpEntity(response.contentType, response.content))
          }
        }
    }
  }

  private def sseEncode[A <: Event: JsonLdEncoder](
      envelope: Envelope[A]
  )(implicit jo: JsonKeyOrdering, cr: RemoteContextResolution): IO[RdfError, ServerSentEvent] =
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

  sealed trait ToResponseJsonLd {
    def apply(statusOverride: Option[StatusCode]): Route
  }

  object ToResponseJsonLd extends LowPrioToResponseJsonLd {
    private[directives] def apply[A: JsonLdEncoder](
        status: => StatusCode,
        headers: => Seq[HttpHeader],
        io: UIO[A]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      new ToResponseJsonLd {
        override def apply(statusOverride: Option[StatusCode]): Route =
          toResponseJsonLd(statusOverride.getOrElse(status), headers, io)
      }

    private[directives] def apply[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
        status: => StatusCode,
        headers: => Seq[HttpHeader],
        io: IO[E, A]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      new ToResponseJsonLd {
        override def apply(statusOverride: Option[StatusCode]): Route =
          toResponseJsonLd(statusOverride.getOrElse(status), headers, io.attempt)
      }

    implicit def FileResponseIOSupport[E: JsonLdEncoder: HttpResponseFields](
        io: IO[E, FileResponse]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      new ToResponseJsonLd {
        override def apply(statusOverride: Option[StatusCode]): Route =
          toResponseJsonLd(statusOverride.getOrElse(OK), io)
      }

    implicit def FileResponseSupport(
        value: FileResponse
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      new ToResponseJsonLd {
        override def apply(statusOverride: Option[StatusCode]): Route =
          toResponseJsonLd(statusOverride.getOrElse(OK), UIO.pure(value))
      }

    implicit def UIOSupport[A: JsonLdEncoder](
        io: UIO[A]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      apply(OK, Seq.empty, io)

    implicit def UIOSearchResultsSupport[A](
        io: UIO[SearchResults[A]]
    )(implicit
        s: Scheduler,
        cr: RemoteContextResolution,
        jo: JsonKeyOrdering,
        S: SearchEncoder[A],
        extraCtx: ContextValue
    ): ToResponseJsonLd =
      apply(OK, Seq.empty, io)

    implicit def IOSupport[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
        io: IO[E, A]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      apply(OK, Seq.empty, io)

    implicit def valueSupport[A: JsonLdEncoder: HttpResponseFields](
        value: A
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      apply(value.status, value.headers, UIO.pure(value))

    implicit def streamSupport[E <: Event: JsonLdEncoder](
        stream: Stream[Task, Envelope[E]]
    )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ToResponseJsonLd =
      new ToResponseJsonLd {
        override def apply(statusOverride: Option[StatusCode]): Route =
          toResponseJsonLd(statusOverride.getOrElse(OK), stream)
      }

    implicit def IOStreamSupport[E: JsonLdEncoder: HttpResponseFields, A <: Event: JsonLdEncoder](
        io: IO[E, Stream[Task, Envelope[A]]]
    )(implicit s: Scheduler, jo: JsonKeyOrdering, cr: RemoteContextResolution): ToResponseJsonLd =
      new ToResponseJsonLd {
        override def apply(statusOverride: Option[StatusCode]): Route =
          toResponseJsonLd(statusOverride.getOrElse(OK), io)
      }
  }

  sealed trait LowPrioToResponseJsonLd {
    implicit def valueNoStatusCodeSupport[A: JsonLdEncoder](
        value: A
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      ToResponseJsonLd(OK, Seq.empty, UIO.pure(value))
  }

  sealed trait DiscardEntityToResponseJsonLd {
    def apply(statusOverride: Option[StatusCode]): Route
  }

  object DiscardEntityToResponseJsonLd extends LowPrioDiscardEntityToResponseJsonLd {

    private[directives] def apply[A: JsonLdEncoder](
        status: => StatusCode,
        headers: => Seq[HttpHeader],
        io: UIO[A]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): DiscardEntityToResponseJsonLd = {
      new DiscardEntityToResponseJsonLd {
        override def apply(statusOverride: Option[StatusCode]): Route =
          extractRequest { request =>
            extractMaterializer { implicit mat =>
              request.discardEntityBytes()
              toResponseJsonLd(statusOverride.getOrElse(status), headers, io)
            }
          }
      }
    }

    implicit def UIODiscardSupport[A: JsonLdEncoder](
        io: UIO[A]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): DiscardEntityToResponseJsonLd =
      apply(StatusCodes.OK, Seq.empty, io)

    implicit def valueDiscardSupport[A: JsonLdEncoder: HttpResponseFields](
        value: A
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): DiscardEntityToResponseJsonLd =
      apply(value.status, value.headers, UIO.pure(value))
  }

  sealed trait LowPrioDiscardEntityToResponseJsonLd {
    implicit def valueNoStatusCodeDiscardSupport[A: JsonLdEncoder](
        value: A
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): DiscardEntityToResponseJsonLd =
      DiscardEntityToResponseJsonLd(OK, Seq.empty, UIO.pure(value))
  }

  private type Result = (StatusCode, Seq[HttpHeader], JsonLd)

  // From the RFC 2047: "=?" charset "?" encoding "?" encoded-text "?="
  private def attachmentString(filename: String): String = {
    val encodedFilename = Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8))
    s"=?UTF-8?B?$encodedFilename?="
  }

}
