package ch.epfl.bluebrain.nexus.delta.routes.directives

import java.util.UUID

import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.ContentNegotiator.Alternative
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{JsonLd, JsonLdEncoder}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.{HttpResponseFields, JsonLdFormat, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.syntax._
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import streamz.converter._

import scala.util.Try

object DeltaDirectives extends UriDirectives with RdfMarshalling {

  // order is important
  private val mediaTypes =
    Seq(
      `application/ld+json`,
      `application/json`,
      `application/n-triples`,
      `text/vnd.graphviz`
    )

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

  private def jsonldFormat[A: JsonLdEncoder](
      io: UIO[Option[A]],
      successStatus: => StatusCode,
      successHeaders: => Seq[HttpHeader]
  )(implicit cr: RemoteContextResolution): Directive1[IO[RdfError, Result[JsonLd]]] =
    jsonLdFormat.map {
      case JsonLdFormat.Compacted => io.flatMap { v => Result.compactedJsonLd(v, successStatus, successHeaders) }
      case JsonLdFormat.Expanded  => io.flatMap { v => Result.expandedJsonLd(v, successStatus, successHeaders) }
    }

  private def jsonldFormat[A: JsonLdEncoder, E: JsonLdEncoder: HttpResponseFields](
      io: IO[E, Option[A]],
      successStatus: => StatusCode,
      successHeaders: => Seq[HttpHeader]
  )(implicit cr: RemoteContextResolution): Directive1[IO[RdfError, Result[JsonLd]]] =
    jsonLdFormat.map {
      case JsonLdFormat.Compacted =>
        io.attempt.flatMap {
          case Left(err)    =>
            err.toCompactedJsonLd.map(v => Result(err.status, err.headers, v: JsonLd))
          case Right(value) => Result.compactedJsonLd(value, successStatus, successHeaders)
        }
      case JsonLdFormat.Expanded  =>
        io.attempt.flatMap {
          case Left(err)    => err.toExpandedJsonLd.map(v => Result(err.status, err.headers, v: JsonLd))
          case Right(value) => Result.expandedJsonLd(value, successStatus, successHeaders)
        }
    }

  /**
    * Extracts the first mediaType found in the ''Accept'' Http request header that matches the delta service ''mediaTypes''.
    * If the Accept header does not match any of the service supported ''mediaTypes'',
    * an [[UnacceptedResponseContentTypeRejection]] is returned
    */
  private def requestMediaType: Directive1[MediaType] =
    extractRequest.flatMap { req =>
      val ct       = new MediaTypeNegotiator(req.headers)
      val accepted = if (ct.acceptedMediaRanges.isEmpty) List(MediaRanges.`*/*`) else ct.acceptedMediaRanges
      accepted.foldLeft[Option[MediaType]](None) {
        case (s @ Some(_), _) => s
        case (None, mr)       => mediaTypes.find(mt => mr.matches(mt))
      } match {
        case Some(value) => provide(value)
        case None        => reject(UnacceptedResponseContentTypeRejection(mediaTypes.map(mt => Alternative(mt)).toSet))
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
      io: UIO[Option[A]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): Route =
    requestMediaType {
      case mediaType if mediaType == `application/ld+json` =>
        jsonldFormat(io, status, headers).apply { formatted =>
          onSuccess(formatted.runToFuture) { case Result(status, headers, jsonLd) =>
            complete(status, headers, jsonLd)
          }
        }

      case mediaType if mediaType == `application/json` =>
        jsonldFormat(io, status, headers).apply { formatted =>
          onSuccess(formatted.runToFuture) { case Result(status, headers, jsonLd) =>
            complete(status, headers, jsonLd.json)
          }
        }

      case mediaType if mediaType == `application/n-triples` =>
        val f = io.flatMap { v => Result.nTriples(v, status, headers) }.runToFuture
        onSuccess(f) { case Result(status, headers, ntriples) => complete(status, headers, ntriples) }

      case mediaType if mediaType == `text/vnd.graphviz`     =>
        val f = io.flatMap { v => Result.dot(v, status, headers) }.runToFuture
        onSuccess(f) { case Result(status, headers, dot) => complete(status, headers, dot) }

      case _                                                 =>
        reject(UnacceptedResponseContentTypeRejection(mediaTypes.toSet.map((mt: MediaType) => Alternative(mt))))
    }

  private def toResponseJsonLd[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
      status: => StatusCode,
      headers: => Seq[HttpHeader],
      io: IO[E, Option[A]]
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): Route =
    requestMediaType {
      case mediaType if mediaType == `application/ld+json` =>
        jsonldFormat(io, status, headers).apply { formatted =>
          onSuccess(formatted.runToFuture) { case Result(status, headers, jsonLd) =>
            complete(status, headers, jsonLd)
          }
        }

      case mediaType if mediaType == `application/json` =>
        jsonldFormat(io, status, headers).apply { formatted =>
          onSuccess(formatted.runToFuture) { case Result(status, headers, jsonLd) =>
            complete(status, headers, jsonLd.json)
          }
        }

      case mediaType if mediaType == `application/n-triples` =>
        val formatted = io.attempt.flatMap {
          case Left(err)    => err.toNTriples.map(v => Result(err.status, err.headers, v))
          case Right(value) => Result.nTriples(value, status, headers)
        }
        onSuccess(formatted.runToFuture) { case Result(status, headers, ntriples) =>
          complete(status, headers, ntriples)
        }

      case mediaType if mediaType == `text/vnd.graphviz` =>
        val formatted = io.attempt.flatMap {
          case Left(err)    => err.toDot.map(v => Result(err.status, err.headers, v))
          case Right(value) => Result.dot(value, status, headers)
        }
        onSuccess(formatted.runToFuture) { case Result(status, headers, dot) =>
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
        data = jsonLd.json.sort.noSpaces,
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
        io: UIO[Option[A]]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      new ToResponseJsonLd {
        override def apply(statusOverride: Option[StatusCode]): Route =
          toResponseJsonLd(statusOverride.getOrElse(status), headers, io)
      }

    private[directives] def apply[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
        status: => StatusCode,
        headers: => Seq[HttpHeader],
        io: IO[E, Option[A]]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      new ToResponseJsonLd {
        override def apply(statusOverride: Option[StatusCode]): Route =
          toResponseJsonLd(statusOverride.getOrElse(status), headers, io)
      }

    implicit def UIOOptSupport[A: JsonLdEncoder](
        io: UIO[Option[A]]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      apply(OK, Seq.empty, io)

    implicit def UIOSupport[A: JsonLdEncoder](
        io: UIO[A]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      apply(OK, Seq.empty, io.map(Some.apply))

    implicit def UIOSearchResultsSupport[A](
        io: UIO[SearchResults[A]]
    )(implicit
        s: Scheduler,
        cr: RemoteContextResolution,
        jo: JsonKeyOrdering,
        S: SearchEncoder[A],
        extraCtx: ContextValue
    ): ToResponseJsonLd =
      apply(OK, Seq.empty, io.map(Some.apply))

    implicit def IOOptSupport[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
        io: IO[E, Option[A]]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      apply(OK, Seq.empty, io)

    implicit def IOSupport[E: JsonLdEncoder: HttpResponseFields, A: JsonLdEncoder](
        io: IO[E, A]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      apply(OK, Seq.empty, io.map(Some.apply))

    implicit def valueSupport[A: JsonLdEncoder: HttpResponseFields](
        value: A
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): ToResponseJsonLd =
      apply(value.status, value.headers, UIO.pure(Some(value)))

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
      ToResponseJsonLd(OK, Seq.empty, UIO.pure(Some(value)))
  }

  sealed trait DiscardEntityToResponseJsonLd {
    def apply(statusOverride: Option[StatusCode]): Route
  }

  object DiscardEntityToResponseJsonLd extends LowPrioDiscardEntityToResponseJsonLd {

    private[directives] def apply[A: JsonLdEncoder](
        status: => StatusCode,
        headers: => Seq[HttpHeader],
        io: UIO[Option[A]]
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

    implicit def UIOOptDiscardSupport[A: JsonLdEncoder](
        io: UIO[Option[A]]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): DiscardEntityToResponseJsonLd =
      apply(StatusCodes.OK, Seq.empty, io)

    implicit def UIODiscardSupport[A: JsonLdEncoder](
        io: UIO[A]
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): DiscardEntityToResponseJsonLd =
      apply(StatusCodes.OK, Seq.empty, io.map(Some.apply))

    implicit def valueDiscardSupport[A: JsonLdEncoder: HttpResponseFields](
        value: A
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): DiscardEntityToResponseJsonLd =
      apply(value.status, value.headers, UIO.pure(Some(value)))
  }

  sealed trait LowPrioDiscardEntityToResponseJsonLd {
    implicit def valueNoStatusCodeDiscardSupport[A: JsonLdEncoder](
        value: A
    )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): DiscardEntityToResponseJsonLd =
      DiscardEntityToResponseJsonLd(OK, Seq.empty, UIO.pure(Some(value)))
  }

  private val notFound: ServiceError = ServiceError.NotFound

  final private[routes] case class Result[C](statusCode: StatusCode, headers: Seq[HttpHeader], content: C)

  private[routes] object Result {

    /**
      * Constructs a result encoding the value as a [[CompactedJsonLd]]
      */
    def compactedJsonLd[A: JsonLdEncoder](value: Option[A], successStatus: StatusCode, successHeaders: Seq[HttpHeader])(
        implicit cr: RemoteContextResolution
    ): IO[RdfError, Result[JsonLd]] =
      value.fold(notFound.toCompactedJsonLd.map(v => Result(StatusCodes.NotFound, Nil, v: JsonLd))) { r: A =>
        r.toCompactedJsonLd.map { v => Result(successStatus, successHeaders, v) }
      }

    /**
      * Constructs a result encoding the value as a [[ExpandedJsonLd]]
      */
    def expandedJsonLd[A: JsonLdEncoder](value: Option[A], successStatus: StatusCode, successHeaders: Seq[HttpHeader])(
        implicit cr: RemoteContextResolution
    ): IO[RdfError, Result[JsonLd]] =
      value.fold(notFound.toExpandedJsonLd.map(v => Result(StatusCodes.NotFound, Nil, v: JsonLd))) { r: A =>
        r.toExpandedJsonLd.map { v => Result(successStatus, successHeaders, v) }
      }

    /**
      * Constructs a result encoding the value as a [[NTriples]]
      */
    def nTriples[A: JsonLdEncoder](value: Option[A], successStatus: StatusCode, successHeaders: Seq[HttpHeader])(
        implicit cr: RemoteContextResolution
    ): IO[RdfError, Result[NTriples]] =
      value.fold(notFound.toNTriples.map(v => Result(StatusCodes.NotFound, Nil, v))) { r: A =>
        r.toNTriples.map { v => Result(successStatus, successHeaders, v) }
      }

    /**
      * Constructs a result encoding the value as a [[Dot]]
      */
    def dot[A: JsonLdEncoder](value: Option[A], successStatus: StatusCode, successHeaders: Seq[HttpHeader])(implicit
        cr: RemoteContextResolution
    ): IO[RdfError, Result[Dot]] =
      value.fold(notFound.toDot.map(v => Result(StatusCodes.NotFound, Nil, v))) { r: A =>
        r.toDot.map { v => Result(successStatus, successHeaders, v) }
      }

  }
}
